#!/usr/bin/env bash
#
# JSONL 파일을 Elasticsearch _bulk API로 색인.
#
# 왜 chunk로 나눠 보내는가:
#   - 한 요청이 너무 크면 http.max_content_length(기본 100MB)를 넘어 413 리턴.
#   - 한 요청 내 실패 시 전체 재전송 비용이 커진다.
#   - 1k~5k docs/batch가 일반적인 스윗스팟 (문서 크기에 따라 조정).
#
# 왜 apply-template.sh를 먼저 실행해야 하는가:
#   - products-template은 index_patterns="products-*"로 매칭된다.
#   - 첫 색인이 템플릿 적용보다 먼저 오면 default mapping으로 인덱스가 생성되어
#     dynamic: strict / nested options / ngram 분석기가 전부 빠진 채 고정된다.
#
# 사용법:
#   scripts/bulk-index.sh [JSONL]
#   ES_URL=http://localhost:9200 INDEX=products-v1 BATCH_SIZE=2000 \
#     scripts/bulk-index.sh dummy/products.jsonl

set -euo pipefail

ES_URL="${ES_URL:-http://localhost:9200}"
INDEX="${INDEX:-products-v1}"
BATCH_SIZE="${BATCH_SIZE:-2000}"
INPUT="${1:-dummy/products.jsonl}"

if [[ ! -f "$INPUT" ]]; then
  echo "input not found: $INPUT" >&2
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "curl required" >&2
  exit 1
fi

TOTAL=$(wc -l < "$INPUT" | tr -d ' ')
echo "bulk indexing: ${TOTAL} docs -> ${ES_URL}/${INDEX} (batch=${BATCH_SIZE})"

TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

# split은 파일 단위로 동일 크기 chunk를 만들어준다. stdin 스트리밍보다 디스크 I/O는 늘지만
# chunk 실패 시 재시도/디버깅이 훨씬 쉽다.
split -l "$BATCH_SIZE" "$INPUT" "$TMPDIR/chunk-"

count=0
start_ts=$(date +%s)

for chunk in "$TMPDIR"/chunk-*; do
  BULK="${chunk}.bulk"
  # _bulk는 ndjson 형식 — 각 data line 앞에 action line을 넣어준다.
  # index action의 _index는 URL에서 지정했으므로 body에서는 생략.
  awk '{ print "{\"index\":{}}"; print }' "$chunk" > "$BULK"

  RESP_FILE="${TMPDIR}/resp.json"
  http_code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" \
    -H "Content-Type: application/x-ndjson" \
    -XPOST "${ES_URL}/${INDEX}/_bulk" \
    --data-binary "@${BULK}")

  if [[ "$http_code" != "200" ]]; then
    echo "bulk request failed: http ${http_code}" >&2
    cat "$RESP_FILE" >&2
    exit 1
  fi

  # _bulk는 개별 문서 실패가 있어도 200을 반환한다 (errors:true).
  # python3로 파싱하는 이유: jq 의존성을 줄이기 위해 (macOS 기본 설치 기준).
  if python3 -c "import json,sys; d=json.load(open('${RESP_FILE}')); sys.exit(1 if d.get('errors') else 0)"; then
    :
  else
    echo "bulk response had errors — first few items:" >&2
    python3 -c "
import json
d = json.load(open('${RESP_FILE}'))
for it in d.get('items', [])[:5]:
    print(json.dumps(it, ensure_ascii=False))
" >&2
    exit 1
  fi

  chunk_size=$(wc -l < "$chunk" | tr -d ' ')
  count=$((count + chunk_size))
  printf "  %d / %d\n" "$count" "$TOTAL"
done

elapsed=$(( $(date +%s) - start_ts ))
echo "indexed ${count} docs in ${elapsed}s"

echo "refresh + count:"
curl -s -XPOST "${ES_URL}/${INDEX}/_refresh" > /dev/null
curl -s "${ES_URL}/${INDEX}/_count" | python3 -m json.tool

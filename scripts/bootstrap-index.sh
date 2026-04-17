#!/usr/bin/env bash
# products-v1 write alias + initial rollover index 부트스트랩.
#
# 구조:
#   write alias : products-v1
#     └─ backing index : products-v1-000001 (is_write_index=true)
#     └─ backing index : products-v1-000002 (rollover 시 자동 생성)
#     ...
#
# - bulk-indexer는 alias(products-v1)로 write → ES가 현재 write index로 라우팅
# - ILM의 rollover action이 기준 도달 시 새 backing index 생성 + alias 자동 전환
# - 구 backing index는 read-only 상태로 남아 warm → cold → delete 단계 전이
#
# 재실행 가능(idempotent).

set -euo pipefail

ES="${ES_HOST:-http://localhost:9200}"
TEMPLATE_FILE="$(dirname "$0")/../infra/es/templates/products-template.json"
ILM_FILE="$(dirname "$0")/../infra/es/ilm/products-ilm.json"
ALIAS="products-v1"
INITIAL_INDEX="products-v1-000001"

echo "[1/5] 이전 잔여 리소스 정리"

# 과거 실험에서 남았을 수 있는 legacy 리소스들.
# 존재하지 않으면 404라 실패인데, -f 옵션으로 silent pass.
if curl -sSf -o /dev/null "$ES/_data_stream/products-v1"; then
  echo "  → 남아있는 data stream products-v1 삭제"
  curl -sS -XDELETE "$ES/_data_stream/products-v1" | sed 's/^/    /'
  echo
fi

if curl -sSf -o /dev/null "$ES/products"; then
  echo "  → 남아있는 legacy index 'products' 삭제"
  curl -sS -XDELETE "$ES/products" | sed 's/^/    /'
  echo
fi

echo "[2/5] ILM policy 재적용"
curl -sS -XPUT "$ES/_ilm/policy/products-ilm" \
  -H 'Content-Type: application/json' \
  --data-binary "@$ILM_FILE" | sed 's/^/  /'
echo

echo "[3/5] index template 재적용"
curl -sS -XPUT "$ES/_index_template/products-template" \
  -H 'Content-Type: application/json' \
  --data-binary "@$TEMPLATE_FILE" | sed 's/^/  /'
echo

echo "[4/5] 초기 write index + alias 생성"
# alias가 이미 어떤 인덱스를 가리키고 있으면 스킵.
if curl -sSf -o /dev/null "$ES/_alias/$ALIAS"; then
  echo "  → alias '$ALIAS' 이미 존재 (skip)"
else
  curl -sS -XPUT "$ES/$INITIAL_INDEX" \
    -H 'Content-Type: application/json' \
    -d "{
      \"aliases\": {
        \"$ALIAS\": { \"is_write_index\": true }
      }
    }" | sed 's/^/  /'
  echo
fi

echo "[5/5] 결과 확인"
echo "  • alias → index 매핑:"
curl -sS "$ES/_alias/$ALIAS?pretty" | sed 's/^/    /'
echo "  • ILM 상태:"
curl -sS "$ES/$ALIAS/_ilm/explain?pretty" | sed 's/^/    /'

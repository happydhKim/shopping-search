#!/usr/bin/env bash
#
# Phase 2 데이터 투입 + rollover 트리거
#
# 동작:
#   1) data stream 'products-stream' 생성 (첫 문서 색인 시 자동)
#   2) 더미 데이터 bulk 색인
#   3) 수동 rollover 트리거 (ILM min_age 대기 없이 즉시 새 인덱스 생성)
#   4) ILM step 수동 진행 (warm/cold 이동 관찰용)
#
set -euo pipefail

ES_URL="${ES_URL:-http://localhost:9200}"
STREAM="products-stream"
BATCH_SIZE=500
TOTAL_DOCS="${1:-5000}"

echo "=== Phase 2 데이터 투입 ==="
echo "  data stream: ${STREAM}"
echo "  총 문서 수:  ${TOTAL_DOCS}"
echo

# ─────────────────────────────────────────
# 1. Bulk 색인 — data stream에 _bulk POST
# ─────────────────────────────────────────
echo "[1/3] Bulk 색인 (${TOTAL_DOCS} docs, batch=${BATCH_SIZE})"

brands=("나이키" "아디다스" "뉴발란스" "퓨마" "리복" "언더아머" "컨버스" "반스" "아식스" "호카")
categories=("스포츠>운동화" "스포츠>런닝화" "패션>스니커즈" "아웃도어>트레킹화" "스포츠>축구화")

doc_count=0
while (( doc_count < TOTAL_DOCS )); do
  bulk_body=""
  batch_end=$(( doc_count + BATCH_SIZE ))
  if (( batch_end > TOTAL_DOCS )); then
    batch_end=$TOTAL_DOCS
  fi

  while (( doc_count < batch_end )); do
    brand_idx=$(( RANDOM % ${#brands[@]} ))
    cat_idx=$(( RANDOM % ${#categories[@]} ))
    price=$(( RANDOM % 200000 + 10000 ))
    sales=$(( RANDOM % 10000 ))
    score=$(( RANDOM % 50 + 1 ))
    stock=$(( RANDOM % 100 ))
    ts=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    bulk_body+='{"create":{}}'$'\n'
    bulk_body+="{\"@timestamp\":\"${ts}\",\"product_id\":\"P${doc_count}\",\"title\":\"${brands[$brand_idx]} 운동화 모델-${doc_count}\",\"brand\":\"${brands[$brand_idx]}\",\"brand_id\":\"B${brand_idx}\",\"category_path\":\"${categories[$cat_idx]}\",\"category_leaf\":\"$(echo "${categories[$cat_idx]}" | sed 's/.*>//')\",\"tags\":[\"운동화\",\"신발\"],\"price\":${price},\"original_price\":$(( price + 10000 )),\"discount_rate\":0.1,\"currency\":\"KRW\",\"stock\":${stock},\"in_stock\":$([ $stock -gt 0 ] && echo true || echo false),\"sales_count\":${sales},\"view_count\":$(( sales * 3 )),\"review_count\":$(( sales / 10 )),\"review_score\":$(echo "scale=1; $score / 10" | bc),\"seller_id\":\"S$(( RANDOM % 50 ))\",\"shipping_free\":$([ $((RANDOM % 2)) -eq 0 ] && echo true || echo false),\"image_url\":\"https://example.com/img/P${doc_count}.jpg\",\"created_at\":\"${ts}\",\"updated_at\":\"${ts}\"}"$'\n'
    doc_count=$(( doc_count + 1 ))
  done

  result=$(curl -fsS -X POST "${ES_URL}/${STREAM}/_bulk" \
    -H "Content-Type: application/x-ndjson" \
    --data-binary "${bulk_body}" 2>&1)

  errors=$(echo "$result" | sed -n 's/.*"errors":\([a-z]*\).*/\1/p')
  if [[ "$errors" == "true" ]]; then
    echo "  WARNING: bulk errors at doc_count=${doc_count}"
  fi

  printf "\r  indexed: %d / %d" "$doc_count" "$TOTAL_DOCS"
done
echo

# ─────────────────────────────────────────
# 2. 수동 rollover
# ─────────────────────────────────────────
echo
echo "[2/3] 수동 rollover 트리거"
curl -fsS -X POST "${ES_URL}/${STREAM}/_rollover?pretty" 2>&1 | head -15

# ─────────────────────────────────────────
# 3. 현황 확인
# ─────────────────────────────────────────
echo
echo "[3/3] Data stream 상태"
curl -fsS "${ES_URL}/_data_stream/${STREAM}?pretty" 2>&1 | head -30
echo
echo "DONE — 다음 단계: ./scripts/phase2/check-tier.sh"

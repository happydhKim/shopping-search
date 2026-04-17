#!/usr/bin/env bash
#
# Phase 2 클러스터 부트스트랩
#   1) Phase 1 클러스터 정지
#   2) docker compose up (5-node)
#   3) cluster health green 대기
#   4) ILM 정책 등록
#   5) products data stream 템플릿 등록
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INFRA_DIR="${PROJECT_ROOT}/infra"
ES_URL="${ES_URL:-http://localhost:9200}"

# ─────────────────────────────────────────
# 1. Phase 1 클러스터가 떠 있으면 정지
# ─────────────────────────────────────────
echo "[1/5] Phase 1 클러스터 정리"
if docker compose -f "${PROJECT_ROOT}/infra/docker-compose.yml" ps --quiet 2>/dev/null | grep -q .; then
  docker compose -f "${PROJECT_ROOT}/infra/docker-compose.yml" down
  echo "  Phase 1 정지 완료"
else
  echo "  Phase 1 실행 중 아님 — skip"
fi

# ─────────────────────────────────────────
# 2. compose up
# ─────────────────────────────────────────
echo "[2/5] docker compose up -d (5-node)"
cd "${INFRA_DIR}"
docker compose up -d

# ─────────────────────────────────────────
# 3. cluster health 대기
# ─────────────────────────────────────────
echo "[3/5] cluster health green 대기 (최대 180s)"
deadline=$(( $(date +%s) + 180 ))
while true; do
  status=$(curl -fsS "${ES_URL}/_cluster/health" 2>/dev/null \
    | sed -n 's/.*"status":"\([a-z]*\)".*/\1/p' || echo "")
  if [[ "$status" == "green" || "$status" == "yellow" ]]; then
    echo "  cluster status: $status"
    break
  fi
  if (( $(date +%s) > deadline )); then
    echo "  TIMEOUT — cluster not ready"
    docker compose ps
    exit 1
  fi
  sleep 3
done

# ─────────────────────────────────────────
# 4. ILM 정책 등록
# ─────────────────────────────────────────
echo "[4/5] ILM 정책 등록: products-ilm"
"${PROJECT_ROOT}/scripts/apply-ilm.sh"

# ─────────────────────────────────────────
# 5. 인덱스 템플릿 등록 (data stream)
# ─────────────────────────────────────────
echo "[5/5] products-template 등록 (data stream)"
"${PROJECT_ROOT}/scripts/apply-template.sh"

echo
echo "DONE — Phase 2 cluster ready (5-node Hot/Warm/Cold)"
echo "  Elasticsearch:  http://localhost:9200"
echo "  Prometheus:     http://localhost:9090"
echo "  Grafana:        http://localhost:3000  (admin/admin)"
echo
echo "다음 단계:"
echo "  ./scripts/seed-data.sh       — 데이터 투입 + rollover"
echo "  ./scripts/check-tier.sh      — tier 분포 확인"
echo "  ./scripts/observe-warm.sh    — warm 이동 후 segment/heap 비교"

#!/usr/bin/env bash
#
# Phase 1 클러스터 부트스트랩
#   1) Docker Desktop의 vm.max_map_count 점검
#   2) docker compose up
#   3) cluster health green 대기
#   4) products 인덱스 템플릿 적용
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INFRA_DIR="${PROJECT_ROOT}/infra/phase1"

# ─────────────────────────────────────────
# 1. 사전 점검
# ─────────────────────────────────────────
echo "[1/4] vm.max_map_count 점검"

# Linux/Docker Desktop 모두 ES는 mmap을 많이 쓰므로 262144 이상 권장
if [[ "$(uname)" == "Linux" ]]; then
  current=$(sysctl -n vm.max_map_count)
  if [[ "$current" -lt 262144 ]]; then
    echo "  vm.max_map_count=${current} (권장: 262144)"
    echo "  실행: sudo sysctl -w vm.max_map_count=262144"
    exit 1
  fi
else
  echo "  macOS/Docker Desktop — Settings → Resources → Memory ≥ 8G 확인 필요"
fi

# ─────────────────────────────────────────
# 2. compose up
# ─────────────────────────────────────────
echo "[2/4] docker compose up -d"
cd "${INFRA_DIR}"
docker compose up -d

# ─────────────────────────────────────────
# 3. cluster health 대기
# ─────────────────────────────────────────
echo "[3/4] cluster health green 대기 (최대 120s)"
deadline=$(( $(date +%s) + 120 ))
while true; do
  status=$(curl -fsS http://localhost:9200/_cluster/health 2>/dev/null | sed -n 's/.*"status":"\([a-z]*\)".*/\1/p' || echo "")
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
# 4. 인덱스 템플릿 등록
# ─────────────────────────────────────────
echo "[4/4] products-template 등록"
"${PROJECT_ROOT}/scripts/phase1/apply-template.sh"

echo
echo "DONE — Phase 1 cluster ready"
echo "  Elasticsearch:  http://localhost:9200"
echo "  Prometheus:     http://localhost:9090"
echo "  Grafana:        http://localhost:3000  (admin/admin)"
echo
echo "다음 단계: ./scripts/phase1/health.sh"

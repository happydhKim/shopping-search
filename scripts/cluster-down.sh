#!/usr/bin/env bash
#
# Phase 2 클러스터 정지
#   기본: 컨테이너만 중지 (볼륨 보존)
#   --purge: 볼륨까지 삭제 (데이터 초기화)
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INFRA_DIR="${PROJECT_ROOT}/infra"

cd "${INFRA_DIR}"

if [[ "${1:-}" == "--purge" ]]; then
  echo "WARNING: ES 데이터 볼륨까지 삭제합니다"
  read -p "계속? (y/N) " confirm
  if [[ "$confirm" == "y" ]]; then
    docker compose down -v
    echo "DONE — 모든 데이터 삭제됨"
  else
    echo "취소"
  fi
else
  docker compose down
  echo "DONE — 컨테이너 중지 (볼륨은 보존됨)"
  echo "데이터까지 지우려면: $0 --purge"
fi

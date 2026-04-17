#!/usr/bin/env bash
#
# Debezium MySQL 커넥터 등록.
#
# 왜 스크립트로 POST 하는가: Kafka Connect는 커넥터 설정을 REST API로만 받는다.
# compose 기동 직후 Connect가 Kafka 토픽(config/offset/status) 준비를 끝낼 때까지
# 짧게 기다린 뒤 등록해야 안정적이다.
#
# idempotent 하게 만들어 재실행 안전:
#   - 같은 이름의 커넥터가 있으면 DELETE → 재등록. 개발 중 connector.json 바꿀 때 편리.
#   - 프로덕션에선 이 방식을 쓰면 재-스냅샷이 돌 수 있어 신중히.

set -euo pipefail

CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONNECTOR_FILE="${PROJECT_ROOT}/infra/debezium/connector.json"
CONNECTOR_NAME="shopping-mysql-connector"

echo "waiting for Connect at ${CONNECT_URL}..."
for i in $(seq 1 60); do
  if curl -sf "${CONNECT_URL}/" > /dev/null; then
    break
  fi
  sleep 2
done

if ! curl -sf "${CONNECT_URL}/" > /dev/null; then
  echo "ERROR: Connect not reachable after 120s" >&2
  exit 1
fi

if curl -sf "${CONNECT_URL}/connectors/${CONNECTOR_NAME}" > /dev/null; then
  echo "connector ${CONNECTOR_NAME} exists — deleting for re-register"
  curl -sf -X DELETE "${CONNECT_URL}/connectors/${CONNECTOR_NAME}"
  sleep 2
fi

echo "registering ${CONNECTOR_NAME}..."
curl -sf -X POST \
  -H "Content-Type: application/json" \
  -d "@${CONNECTOR_FILE}" \
  "${CONNECT_URL}/connectors" | python3 -m json.tool

echo ""
echo "status:"
sleep 3
curl -sf "${CONNECT_URL}/connectors/${CONNECTOR_NAME}/status" | python3 -m json.tool

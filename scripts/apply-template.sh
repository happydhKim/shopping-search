#!/usr/bin/env bash
#
# products data stream 인덱스 템플릿 등록
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ES_URL="${ES_URL:-http://localhost:9200}"
TEMPLATE_FILE="${PROJECT_ROOT}/infra/es/templates/products-template.json"

if [[ ! -f "${TEMPLATE_FILE}" ]]; then
  echo "ERROR: template file not found: ${TEMPLATE_FILE}"
  exit 1
fi

echo "Registering index template: products-template (data stream)"
curl -fsS -X PUT "${ES_URL}/_index_template/products-template" \
  -H "Content-Type: application/json" \
  --data-binary "@${TEMPLATE_FILE}" \
  | sed 's/^/  /'
echo

echo
echo "Verifying:"
curl -fsS "${ES_URL}/_index_template/products-template?pretty" | head -30

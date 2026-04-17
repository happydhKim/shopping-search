#!/usr/bin/env bash
#
# ILM 정책 등록: products-ilm
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ES_URL="${ES_URL:-http://localhost:9200}"
ILM_FILE="${PROJECT_ROOT}/infra/es/ilm/products-ilm.json"

if [[ ! -f "${ILM_FILE}" ]]; then
  echo "ERROR: ILM file not found: ${ILM_FILE}"
  exit 1
fi

echo "Registering ILM policy: products-ilm"
curl -fsS -X PUT "${ES_URL}/_ilm/policy/products-ilm" \
  -H "Content-Type: application/json" \
  --data-binary "@${ILM_FILE}" \
  | sed 's/^/  /'
echo

echo
echo "Verifying:"
curl -fsS "${ES_URL}/_ilm/policy/products-ilm?pretty" | head -40

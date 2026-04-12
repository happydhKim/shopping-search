#!/usr/bin/env bash
#
# Phase 2 warm 이동 후 관찰
#   - ILM poll 가속 → warm phase 강제 이동
#   - warm 노드 1대에서 replica 배치 불가 시 자동으로 replicas=0
#   - force_merge 전후 세그먼트 수 비교
#
set -euo pipefail

ES_URL="${ES_URL:-http://localhost:9200}"

echo "==================================================================="
echo " 1. 현재 인덱스 목록"
echo "==================================================================="
indices=$(curl -fsS "${ES_URL}/_cat/indices/.ds-products-stream*?h=index&s=index" 2>/dev/null)

if [[ -z "$indices" ]]; then
  echo "  products-stream 인덱스 없음. seed-data.sh를 먼저 실행하세요."
  exit 1
fi

echo "$indices"
echo

write_index=$(curl -fsS "${ES_URL}/_data_stream/products-stream" 2>/dev/null \
  | sed -n 's/.*"index_name" *: *"\([^"]*\)".*/\1/p' | tail -1)
echo "  현재 write index: ${write_index}"

old_indices=$(echo "$indices" | grep -v "$write_index" || true)
if [[ -z "$old_indices" ]]; then
  echo "  rollover 된 이전 인덱스가 없음. seed-data.sh에서 rollover를 먼저 하세요."
  exit 1
fi

target=$(echo "$old_indices" | head -1)
echo "  관찰 대상 (이전 인덱스): ${target}"
echo

echo "==================================================================="
echo " 2. ILM poll 가속 + warm phase 이동"
echo "==================================================================="

# ILM poll interval을 1초로 단축
curl -fsS -X PUT "${ES_URL}/_cluster/settings" \
  -H "Content-Type: application/json" \
  -d '{"transient":{"indices.lifecycle.poll_interval":"1s"}}' > /dev/null

sleep 2

# 현재 ILM step 감지
current_phase=$(curl -fsS "${ES_URL}/${target}/_ilm/explain" 2>/dev/null \
  | sed -n 's/.*"phase" *: *"\([^"]*\)".*/\1/p')
current_action=$(curl -fsS "${ES_URL}/${target}/_ilm/explain" 2>/dev/null \
  | sed -n 's/.*"action" *: *"\([^"]*\)".*/\1/p')
current_step=$(curl -fsS "${ES_URL}/${target}/_ilm/explain" 2>/dev/null \
  | sed -n 's/.*"step" *: *"\([^"]*\)".*/\1/p')

echo "  현재 ILM: phase=${current_phase}, action=${current_action}, step=${current_step}"

if [[ "$current_phase" == "warm" ]]; then
  echo "  이미 warm phase — skip"
elif [[ "$current_phase" == "hot" ]]; then
  echo "  ${target} → warm phase 강제 이동"
  curl -fsS -X POST "${ES_URL}/_ilm/move/${target}" \
    -H "Content-Type: application/json" \
    -d "{
      \"current_step\": {
        \"phase\": \"${current_phase}\",
        \"action\": \"${current_action}\",
        \"name\": \"${current_step}\"
      },
      \"next_step\": {
        \"phase\": \"warm\",
        \"action\": \"allocate\",
        \"name\": \"allocate\"
      }
    }" 2>&1 | sed 's/^/  /'
  echo
fi

# warm 노드가 1대일 때 replica가 배치 불가 → replicas=0 설정
echo "  replica=0 설정 (warm 노드 1대에서 replica 배치 불가 방지)"
curl -fsS -X PUT "${ES_URL}/${target}/_settings" \
  -H "Content-Type: application/json" \
  -d '{"index":{"number_of_replicas":0}}' > /dev/null

echo "  20초 대기 (shard relocation + force_merge 완료 대기)..."
sleep 20

echo
echo "==================================================================="
echo " 3. 세그먼트 수 비교 (hot write index vs warm 이전 인덱스)"
echo "==================================================================="
echo "--- Hot (${write_index}) ---"
curl -fsS "${ES_URL}/_cat/segments/${write_index}?v&h=index,shard,segment,docs.count,size,size.memory" 2>&1
echo
echo "--- Warm (${target}) ---"
curl -fsS "${ES_URL}/_cat/segments/${target}?v&h=index,shard,segment,docs.count,size,size.memory" 2>&1
echo

echo "==================================================================="
echo " 4. Shard 위치 확인 — warm 인덱스가 node4로 이동했는지"
echo "==================================================================="
curl -fsS "${ES_URL}/_cat/shards/${target}?v&h=index,shard,prirep,state,node" 2>&1
echo

echo "==================================================================="
echo " 5. ILM 최종 상태"
echo "==================================================================="
curl -fsS "${ES_URL}/${target}/_ilm/explain?pretty" 2>&1 \
  | grep -E '"index"|"phase"|"action"|"step"|"age"'
echo

# poll interval 원복
curl -fsS -X PUT "${ES_URL}/_cluster/settings" \
  -H "Content-Type: application/json" \
  -d '{"transient":{"indices.lifecycle.poll_interval":"10m"}}' > /dev/null

echo
echo "==================================================================="
echo " 해석 가이드"
echo "==================================================================="
echo "  - Hot 인덱스: 세그먼트 여러 개 (bulk 색인 중 refresh 마다 생김)"
echo "  - Warm 인덱스: force_merge로 세그먼트 1개로 합쳐짐"
echo "  - size.memory: 세그먼트 메타의 Heap 점유 (8.x에서는 대부분 0 = off-heap)"
echo "  - node 열: warm 인덱스 shard가 node4(data_warm)에 있어야 정상"
echo "  - replica=0: warm 노드가 1대뿐이므로 replica를 warm tier에 배치 불가"
echo "    → 실운영에서도 warm/cold tier는 replica를 줄이는 것이 일반적"

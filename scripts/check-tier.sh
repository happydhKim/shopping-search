#!/usr/bin/env bash
#
# Phase 2 tier 분포 확인
#   - 노드별 역할 & heap
#   - data stream 인덱스가 어느 노드에 있는지
#   - ILM 각 인덱스의 현재 phase
#
set -euo pipefail

ES_URL="${ES_URL:-http://localhost:9200}"

echo "==================================================================="
echo " 1. 노드 역할 & Heap"
echo "==================================================================="
curl -fsS "${ES_URL}/_cat/nodes?v&h=name,node.role,master,heap.percent,ram.percent,disk.avail"
echo

echo "==================================================================="
echo " 2. Shard 분포 (products-stream 관련)"
echo "==================================================================="
curl -fsS "${ES_URL}/_cat/shards/.ds-products-stream*?v&h=index,shard,prirep,state,docs,store,node" 2>/dev/null \
  || echo "  (아직 products-stream 인덱스 없음)"
echo

echo "==================================================================="
echo " 3. ILM 상태 (각 인덱스의 현재 phase)"
echo "==================================================================="
curl -fsS "${ES_URL}/.ds-products-stream*/_ilm/explain?pretty" 2>/dev/null \
  | grep -E '"index"|"phase"|"action"|"step"' \
  || echo "  (아직 ILM 대상 인덱스 없음)"
echo

echo "==================================================================="
echo " 4. Data Stream 개요"
echo "==================================================================="
curl -fsS "${ES_URL}/_data_stream/products-stream?pretty" 2>/dev/null \
  | head -25 \
  || echo "  (아직 data stream 없음)"

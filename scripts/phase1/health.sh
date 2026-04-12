#!/usr/bin/env bash
#
# Phase 1 cluster 건강도 점검
#   - cluster health
#   - node 목록 + 역할
#   - 인덱스 목록 + shard 분포
#
set -euo pipefail

ES_URL="${ES_URL:-http://localhost:9200}"

echo "==================================================================="
echo " Cluster Health"
echo "==================================================================="
curl -fsS "${ES_URL}/_cluster/health?pretty"

echo
echo "==================================================================="
echo " Nodes (역할 분리 확인)"
echo "==================================================================="
# m=master, d=data, h=hot, c=content, i=ingest
curl -fsS "${ES_URL}/_cat/nodes?v&h=name,node.role,master,heap.percent,ram.percent,cpu,load_1m"

echo
echo "==================================================================="
echo " Indices"
echo "==================================================================="
curl -fsS "${ES_URL}/_cat/indices?v&h=health,status,index,pri,rep,docs.count,store.size"

echo
echo "==================================================================="
echo " Shards 분포"
echo "==================================================================="
curl -fsS "${ES_URL}/_cat/shards?v&h=index,shard,prirep,state,docs,store,node"

echo
echo "==================================================================="
echo " Templates"
echo "==================================================================="
curl -fsS "${ES_URL}/_cat/templates/products*?v&h=name,index_patterns,order"

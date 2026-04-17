#!/usr/bin/env bash
#
# JVM / Heap / GC / 세그먼트 / 스레드풀 관찰 — JD 필수 요건 시연용
#
# "Heap이 위험 수준이면 어떤 명령으로 확인하나요?" 질문에 답하기 위한 스크립트.
# 출력 결과를 캡처해 docs/phase1.md에 첨부하면 좋다.
#
set -euo pipefail

ES_URL="${ES_URL:-http://localhost:9200}"

# JSON 파싱을 위해 jq 권장. 없어도 동작은 함.
have_jq=0
command -v jq >/dev/null 2>&1 && have_jq=1

print_section() {
  echo
  echo "==================================================================="
  echo " $1"
  echo "==================================================================="
}

# ─────────────────────────────────────────
# 1. JVM Heap 사용률
# ─────────────────────────────────────────
print_section "1. JVM Heap (정상 < 70%, 위험 > 85%)"
if [[ $have_jq -eq 1 ]]; then
  curl -fsS "${ES_URL}/_nodes/stats/jvm" \
    | jq -r '.nodes | to_entries[] | "\(.value.name)\t heap_used=\(.value.jvm.mem.heap_used_percent)%\t heap_committed=\(.value.jvm.mem.heap_committed_in_bytes/1024/1024 | floor)MB"'
else
  curl -fsS "${ES_URL}/_cat/nodes?v&h=name,heap.percent,heap.current,heap.max,ram.percent"
fi

# ─────────────────────────────────────────
# 2. GC 횟수/시간
# ─────────────────────────────────────────
print_section "2. GC (Young + Old) — Old GC가 잦으면 위험 신호"
if [[ $have_jq -eq 1 ]]; then
  curl -fsS "${ES_URL}/_nodes/stats/jvm" \
    | jq -r '.nodes | to_entries[] | .value as $n |
        "\($n.name)\n  young: count=\($n.jvm.gc.collectors.young.collection_count) time=\($n.jvm.gc.collectors.young.collection_time_in_millis)ms\n  old:   count=\($n.jvm.gc.collectors.old.collection_count) time=\($n.jvm.gc.collectors.old.collection_time_in_millis)ms"'
else
  curl -fsS "${ES_URL}/_nodes/stats/jvm?human"
fi

# ─────────────────────────────────────────
# 3. 세그먼트별 Heap 점유 (Off-heap vs Heap 비교)
# ─────────────────────────────────────────
print_section "3. Segments — size(파일 = Page Cache 대상) vs size.memory(Heap 점유)"
# 인덱스가 없으면 빈 결과 — 정상
curl -fsS "${ES_URL}/_cat/segments?v&h=index,shard,prirep,segment,size,size.memory" 2>/dev/null || echo "  (no segments yet)"

# ─────────────────────────────────────────
# 4. Thread Pool 포화 여부
# ─────────────────────────────────────────
print_section "4. Thread Pool — rejected > 0이면 큐 포화"
curl -fsS "${ES_URL}/_cat/thread_pool/search,write,get?v&h=node_name,name,active,queue,rejected,completed"

# ─────────────────────────────────────────
# 5. 색인 throttle (Bulk 압박 신호)
# ─────────────────────────────────────────
print_section "5. Indexing throttle — throttle_time이 증가하면 Bulk 배치 크기 줄이기"
if [[ $have_jq -eq 1 ]]; then
  curl -fsS "${ES_URL}/_nodes/stats/indices/indexing" \
    | jq -r '.nodes | to_entries[] | "\(.value.name)\t throttle_time=\(.value.indices.indexing.throttle_time_in_millis)ms\t index_total=\(.value.indices.indexing.index_total)"'
else
  curl -fsS "${ES_URL}/_nodes/stats/indices/indexing?human"
fi

# ─────────────────────────────────────────
# 6. Pending tasks (master 부하)
# ─────────────────────────────────────────
print_section "6. Pending Tasks — master에 쌓이면 클러스터 의사결정 지연"
curl -fsS "${ES_URL}/_cluster/pending_tasks?pretty"

echo
echo "==================================================================="
echo " 해석 가이드"
echo "==================================================================="
cat <<'EOF'
  heap.percent  : 70% 이하 정상, 85% 이상 위험 (Old GC 빈발 신호)
  gc.old.count  : 시간당 1회 이내가 정상. 분당 발생하면 즉시 조사
  size.memory   : 세그먼트가 Heap을 점유하는 양. force merge로 줄일 수 있음
  rejected      : 0이 정상. 양수면 thread pool 큐 포화 = 노드 증설 필요
  throttle_time : 0이 정상. Bulk가 너무 크거나 disk I/O 병목일 때 증가
EOF

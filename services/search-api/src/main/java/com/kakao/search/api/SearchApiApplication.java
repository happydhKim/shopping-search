package com.kakao.search.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Search API — 사용자 질의를 받아 ES에 쿼리를 보내고 결과를 반환하는 조회 전용 서비스.
 *
 * <p>설계 의도:
 * <ul>
 *   <li>색인 파이프라인(enricher, bulk-indexer)과 분리된 독립 서비스. 장애 격리와 스케일링 정책 독립.</li>
 *   <li>ES 접근은 coordinating 노드(node3) 경유 — 쿼리 fan-out/reduce를 data 노드 Heap에서 분리.</li>
 *   <li>PoC 단계에선 BM25 + function score만. Hybrid(RRF + kNN)는 Phase 3 Turn 5에서.</li>
 * </ul>
 */
@SpringBootApplication
public class SearchApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(SearchApiApplication.class, args);
    }
}

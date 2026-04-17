package com.kakao.search.indexer.enricher

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Enricher — Debezium CDC 이벤트를 받아 ES 문서 shape로 조립 후 Kafka에 재발행.
 *
 * 입력 토픽:
 *   - dbserver1.shopping.products     (상품 row 변경)
 *   - dbserver1.shopping.brands       (브랜드명 변경 — 간접 변경 fanout 필요)
 *   - dbserver1.shopping.categories   (카테고리 경로 변경 — 동일)
 *
 * 출력 토픽:
 *   - products-enriched  (bulk-indexer가 소비 → ES _bulk)
 *
 * 설계 근거:
 *   - brand/category는 hot key라 건당 MySQL 조회 시 bulk 처리량이 DB RTT에 묶인다.
 *     Redis TTL 캐시(1h) + miss 시 MySQL fallback + 캐시 갱신 패턴으로 해결.
 *   - brands 테이블 1건 변경이 products 수만 건에 영향 — "간접 변경" fanout을 여기서 처리.
 */
@SpringBootApplication
@EnableKafka
@EnableScheduling
class EnricherApplication

fun main(args: Array<String>) {
    runApplication<EnricherApplication>(*args)
}

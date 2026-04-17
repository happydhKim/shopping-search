package com.kakao.search.indexer.enricher

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

/**
 * Debezium CDC 이벤트 listener.
 *
 * 각 토픽은 독립 group_id가 아니라 같은 group_id를 공유한다 — 이유:
 *   - 같은 Enricher 인스턴스가 모든 토픽을 consume해야 "brand 변경으로 영향받는 product를
 *     동일 트랜잭션 경계 안에서" 처리 가능.
 *   - Kafka Streams로 join을 구현하면 더 깔끔하지만 PoC 규모에서는 오버킬.
 *
 * Debezium tombstone 처리:
 *   - delete.handling.mode=rewrite 이므로 value는 { ..., "__deleted": "true" } 로 옴.
 *   - 추가로 순수 tombstone(value=null) 메시지가 뒤따라올 수 있음 → null 체크로 스킵.
 */
@Component
class DebeziumConsumers(
    private val enrichment: EnrichmentService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["dbserver1.shopping.products"])
    fun onProduct(value: String?, ack: Acknowledgment) {
        try {
            if (value == null) { ack.acknowledge(); return }
            val row = objectMapper.readValue(value, ProductRow::class.java)
            enrichment.publish(enrichment.enrich(row))
            ack.acknowledge()
        } catch (e: Exception) {
            // 단일 메시지 실패가 컨슈머 전체를 멈추지 않도록 log + ack.
            // 프로덕션이라면 DLQ로 전송.
            log.error("product event failed: {}", value?.take(200), e)
            ack.acknowledge()
        }
    }

    @KafkaListener(topics = ["dbserver1.shopping.brands"])
    fun onBrand(value: String?, ack: Acknowledgment) {
        try {
            if (value == null) { ack.acknowledge(); return }
            val row = objectMapper.readValue(value, BrandRow::class.java)
            enrichment.handleBrandChange(row)
            ack.acknowledge()
        } catch (e: Exception) {
            log.error("brand event failed: {}", value?.take(200), e)
            ack.acknowledge()
        }
    }

    @KafkaListener(topics = ["dbserver1.shopping.categories"])
    fun onCategory(value: String?, ack: Acknowledgment) {
        try {
            if (value == null) { ack.acknowledge(); return }
            val row = objectMapper.readValue(value, CategoryRow::class.java)
            enrichment.handleCategoryChange(row)
            ack.acknowledge()
        } catch (e: Exception) {
            log.error("category event failed: {}", value?.take(200), e)
            ack.acknowledge()
        }
    }
}

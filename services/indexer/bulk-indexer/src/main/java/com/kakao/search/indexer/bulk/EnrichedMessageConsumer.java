package com.kakao.search.indexer.bulk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * products-enriched Kafka consumer.
 *
 * <p>배치 플러시 규칙:
 * <ul>
 *   <li>buffer size ≥ 100 → 즉시 flush</li>
 *   <li>또는 5초 경과 → scheduler가 flush</li>
 * </ul>
 *
 * <p>동시성: listener 스레드와 scheduler 스레드가 같은 버퍼를 건드리므로 ReentrantLock 사용.
 * synchronized로도 가능하지만 tryLock으로 scheduler가 무한대기하지 않도록 함.
 *
 * <p>Ack 정책: batch flush 성공 후 해당 batch의 마지막 ack만 커밋.
 * 배치 중 하나라도 실패하면 전체 batch를 재처리 — 멱등성은 ES upsert(id=productId)가 담보.
 */
@Component
public class EnrichedMessageConsumer {
    private static final Logger log = LoggerFactory.getLogger(EnrichedMessageConsumer.class);
    private static final int FLUSH_THRESHOLD = 100;

    private final BulkIndexerService indexer;
    private final List<String> buffer = new ArrayList<>(FLUSH_THRESHOLD * 2);
    private final List<Acknowledgment> pendingAcks = new ArrayList<>(FLUSH_THRESHOLD * 2);
    private final ReentrantLock lock = new ReentrantLock();

    public EnrichedMessageConsumer(BulkIndexerService indexer) {
        this.indexer = indexer;
    }

    @KafkaListener(topics = "products-enriched")
    public void consume(String value, Acknowledgment ack) {
        lock.lock();
        try {
            buffer.add(value);
            pendingAcks.add(ack);
            if (buffer.size() >= FLUSH_THRESHOLD) {
                flushLocked();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 5초마다 강제 flush — 트래픽 적은 시간대에도 최대 지연 5초 보장.
     * tryLock으로 listener가 바쁠 때는 skip (다음 주기에 기회).
     */
    @Scheduled(fixedDelay = 5000)
    public void scheduledFlush() {
        if (!lock.tryLock()) return;
        try {
            if (!buffer.isEmpty()) {
                flushLocked();
            }
        } finally {
            lock.unlock();
        }
    }

    private void flushLocked() {
        List<String> toFlush = new ArrayList<>(buffer);
        List<Acknowledgment> toAck = new ArrayList<>(pendingAcks);
        buffer.clear();
        pendingAcks.clear();

        try {
            indexer.flush(toFlush);
            // batch 내 모든 record ack — Kafka는 마지막 offset까지 일괄 커밋 효과.
            toAck.forEach(Acknowledgment::acknowledge);
        } catch (IOException e) {
            log.error("bulk flush failed — batch will be re-consumed after rebalance", e);
            // ack 하지 않음 → consumer rebalance 혹은 재시작 시 재처리.
            // 주의: 같은 pod가 살아있다면 같은 offset을 다시 읽지 않으므로 at-most-once가 될 수 있다.
            // 프로덕션에선 재시도 큐 + 실패 횟수 초과 시 DLQ 설계 필요.
        }
    }
}

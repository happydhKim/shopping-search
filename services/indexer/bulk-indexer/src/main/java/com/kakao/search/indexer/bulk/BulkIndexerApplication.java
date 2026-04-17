package com.kakao.search.indexer.bulk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bulk Indexer — products-enriched 토픽을 소비해 ES _bulk API로 색인.
 *
 * <p>설계 의도:
 * <ul>
 *   <li>Enricher가 조립한 ES 문서를 받아 최소 로직으로 ES에 밀어넣는 전담 서비스.</li>
 *   <li>100건 또는 5초마다 flush — I/O 효율과 메모리 압박의 스윗스팟.</li>
 *   <li>Bulk 실패 시 개별 실패 아이템만 추려 retry queue로 회부 (PoC에선 로깅만).</li>
 * </ul>
 *
 * @see com.kakao.search.indexer.bulk.BulkIndexerService
 */
@SpringBootApplication
@EnableKafka
@EnableScheduling
public class BulkIndexerApplication {
    public static void main(String[] args) {
        SpringApplication.run(BulkIndexerApplication.class, args);
    }
}

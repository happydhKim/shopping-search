package com.kakao.search.indexer.bulk;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ES _bulk 색인 서비스.
 *
 * <p>버퍼링 전략: {@link EnrichedMessageConsumer}가 수집한 메시지 리스트를 받아
 * 한 번의 _bulk 요청으로 ES에 전송한다.
 *
 * <p>왜 Consumer와 Service를 분리하는가:
 * Kafka listener 스레드에서 ES I/O를 직접 하면 long poll + bulk flush가 얽혀 디버깅이 어렵다.
 * Consumer는 버퍼만, Service는 flush만 담당하도록 분리.
 */
@Service
public class BulkIndexerService {
    private static final Logger log = LoggerFactory.getLogger(BulkIndexerService.class);

    private final ElasticsearchClient es;
    private final ObjectMapper mapper;
    private final ObjectMapper esFieldMapper;
    private final String indexName;

    public BulkIndexerService(
            ElasticsearchClient es,
            @Qualifier("objectMapper") ObjectMapper mapper,
            @Qualifier("esFieldMapper") ObjectMapper esFieldMapper,
            @Value("${es.index}") String indexName) {
        this.es = es;
        this.mapper = mapper;
        this.esFieldMapper = esFieldMapper;
        this.indexName = indexName;
    }

    /**
     * 메시지 리스트를 _bulk 요청으로 변환해 전송.
     *
     * @param messages Enricher의 JSON 직렬화 메시지 리스트
     * @return 실패 아이템 수 (정상 전부면 0)
     */
    public int flush(List<String> messages) throws IOException {
        if (messages.isEmpty()) return 0;

        BulkRequest.Builder req = new BulkRequest.Builder();
        List<String> orderedIds = new ArrayList<>(messages.size());

        for (String raw : messages) {
            JsonNode node = mapper.readTree(raw);
            String op = node.path("op").asText();
            String productId = node.path("productId").asText();
            orderedIds.add(productId);

            if ("DELETE".equals(op)) {
                req.operations(BulkOperation.of(b -> b
                        .delete(d -> d.index(indexName).id(productId))));
            } else {
                // doc을 ES 필드명(snake_case)으로 재직렬화.
                JsonNode docNode = node.get("doc");
                Object doc = esFieldMapper.treeToValue(docNode, Object.class);
                req.operations(BulkOperation.of(b -> b
                        .index(i -> i.index(indexName).id(productId).document(doc))));
            }
        }

        BulkResponse resp = es.bulk(req.build());
        if (!resp.errors()) {
            log.info("bulk indexed {} docs", messages.size());
            return 0;
        }

        int failed = 0;
        for (int i = 0; i < resp.items().size(); i++) {
            BulkResponseItem item = resp.items().get(i);
            if (item.error() != null) {
                failed++;
                log.warn("bulk item failed id={} reason={}",
                        orderedIds.get(i), item.error().reason());
            }
        }
        log.warn("bulk flush: {}/{} failed", failed, messages.size());
        return failed;
    }
}

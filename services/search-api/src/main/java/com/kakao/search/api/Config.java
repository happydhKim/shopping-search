package com.kakao.search.api;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Search API용 ES 클라이언트 구성.
 *
 * <p>왜 coordinating 노드(node3:9202)만 바라보는가:
 * <ul>
 *   <li>검색 요청은 fan-out(각 샤드 조회) → reduce(전역 top-N 병합) 2단계.</li>
 *   <li>reduce 단계의 Heap 사용은 hit 수·aggregation 크기에 비례 → data 노드에서 수행하면
 *       색인 워크로드의 indexing buffer와 경합.</li>
 *   <li>coordinating-only 노드로 reduce를 격리하면 data 노드 Heap은 segment/indexing에 집중.</li>
 * </ul>
 *
 * <p>PoC라 단일 호스트만. 운영이라면 여러 coord 노드를 HttpHost[] 배열로 넣어 round-robin.
 */
@Configuration
public class Config {

    @Value("${es.host}")
    private String esHost;

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        RestClient restClient = RestClient.builder(HttpHost.create(esHost))
                // 짧은 connect timeout으로 노드 장애를 빨리 감지, socket은 쿼리 실행 여유.
                .setRequestConfigCallback(cfg -> cfg.setConnectTimeout(1000).setSocketTimeout(5000))
                .build();
        return new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
    }
}

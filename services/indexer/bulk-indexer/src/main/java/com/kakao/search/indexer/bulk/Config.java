package com.kakao.search.indexer.bulk;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class Config {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${bulk-indexer.consumer.group-id}")
    private String groupId;

    @Value("${es.host}")
    private String esHost;

    /**
     * ObjectMapper — Enricher가 snake_case(필드)+camelCase(wrapper)로 직렬화했으므로 그대로 받는다.
     * wrapper 키(op/productId)는 노드 수준 접근이라 naming 전략 영향 없음.
     * doc 하위 필드는 enricher 측 @JsonNaming(SnakeCase)로 이미 snake_case.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Bean
    public DefaultKafkaConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // max.poll.records는 buffer 크기와 맞춤 — poll 한 번에 최대 flush 임계치까지 받도록.
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }

    /**
     * ES 클라이언트.
     *
     * <p>coordinating 노드(node3, port 9202)로 쿼리를 보낼지 data 노드(node1, 9200)로 보낼지는
     * 정책 문제:
     * <ul>
     *   <li>검색: coordinating으로 → Heap 격리 (Phase 2 설계)</li>
     *   <li>색인: data 노드 직접으로 → 한 홉 줄이기. 여기선 단순화를 위해 node1.</li>
     * </ul>
     */
    @Bean
    public ElasticsearchClient elasticsearchClient() {
        RestClient restClient = RestClient.builder(HttpHost.create(esHost))
                .setRequestConfigCallback(cfg -> cfg.setConnectTimeout(1000).setSocketTimeout(10000))
                .build();
        return new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
    }
}

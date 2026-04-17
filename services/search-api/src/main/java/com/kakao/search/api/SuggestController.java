package com.kakao.search.api;

import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * /suggest — 자동완성 후보 조회 엔드포인트.
 *
 * <p>/search와 분리한 이유: 응답 계약(타이틀 문자열 + product_id만)과 쿼리 구조가 달라
 * 한 컨트롤러에 섞으면 파라미터 혼선이 생긴다. 운영 단계에서 별도 cache/rate-limit 정책을
 * 적용하기에도 엔드포인트가 나뉘어 있는 편이 낫다.
 */
@RestController
@RequestMapping("/suggest")
public class SuggestController {

    private static final Logger log = LoggerFactory.getLogger(SuggestController.class);

    // 너무 짧은 prefix는 의미 있는 후보가 안 나오므로 차단 — 공백은 trim 후 판단.
    private static final int MIN_PREFIX_LEN = 1;
    private static final int DEFAULT_SIZE = 8;
    private static final int MAX_SIZE = 20;

    private final SearchHandler handler;

    public SuggestController(SearchHandler handler) {
        this.handler = handler;
    }

    @GetMapping
    public ResponseEntity<?> suggest(
            @RequestParam(name = "q") String q,
            @RequestParam(name = "size", required = false) Integer sizeParam) throws IOException {

        String prefix = q == null ? "" : q.trim();
        if (prefix.length() < MIN_PREFIX_LEN) {
            return ResponseEntity.ok(Map.of("suggestions", List.of()));
        }

        int size = sizeParam != null ? Math.min(sizeParam, MAX_SIZE) : DEFAULT_SIZE;
        SearchResponse<ObjectNode> resp = handler.suggest(prefix, size);

        List<Map<String, Object>> suggestions = resp.hits().hits().stream()
                .map(h -> {
                    ObjectNode src = h.source();
                    JsonNode title = src == null ? null : src.get("title");
                    JsonNode productId = src == null ? null : src.get("product_id");
                    return Map.<String, Object>of(
                            "product_id", productId == null ? "" : productId.asText(),
                            "title", title == null ? "" : title.asText()
                    );
                })
                .collect(Collectors.toList());

        log.debug("suggest q='{}' n={} took={}ms", prefix, suggestions.size(), resp.took());
        return ResponseEntity.ok(Map.of(
                "suggestions", suggestions,
                "took_ms", resp.took()
        ));
    }
}

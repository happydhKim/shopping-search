package com.kakao.search.api;

import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * /search 엔드포인트.
 *
 * <p>응답 DTO는 별도 클래스 없이 Map으로 감싼다 — PoC 단계에서 hit 구조를 유연하게 관찰하기 위함.
 * 운영이라면 SearchResult record로 고정해 계약을 명시.
 */
@RestController
@RequestMapping("/search")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final SearchHandler handler;
    private final int defaultSize;
    private final int maxSize;

    public SearchController(
            SearchHandler handler,
            @Value("${search.default-size}") int defaultSize,
            @Value("${search.max-size}") int maxSize) {
        this.handler = handler;
        this.defaultSize = defaultSize;
        this.maxSize = maxSize;
    }

    @GetMapping
    public ResponseEntity<?> search(
            @RequestParam(name = "q") String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", required = false) Integer sizeParam) throws IOException {

        // 입력 방어 — 빈 쿼리는 match_all로 해석하지 않고 400.
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "q parameter is required"));
        }

        int size = sizeParam != null ? Math.min(sizeParam, maxSize) : defaultSize;
        int from = Math.max(page, 0) * size;

        SearchResponse<ObjectNode> resp = handler.search(q, from, size);

        List<Map<String, Object>> hits = resp.hits().hits().stream()
                .map(this::toHitView)
                .collect(Collectors.toList());

        long total = resp.hits().total() != null ? resp.hits().total().value() : 0L;

        Map<String, Object> body = Map.of(
                "total", total,
                "took_ms", resp.took(),
                "page", page,
                "size", size,
                "hits", hits
        );
        log.debug("search q='{}' total={} took={}ms", q, total, resp.took());
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> toHitView(Hit<ObjectNode> h) {
        return Map.of(
                "id", h.id(),
                "score", h.score() != null ? h.score() : 0.0,
                "source", h.source()
        );
    }
}

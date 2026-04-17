package com.kakao.search.api;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.LongTermsBucket;
import co.elastic.clients.elasticsearch._types.aggregations.RangeBucket;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
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
import java.util.ArrayList;
import java.util.HashMap;
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
            @RequestParam(name = "size", required = false) Integer sizeParam,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "price_min", required = false) Double priceMin,
            @RequestParam(name = "price_max", required = false) Double priceMax) throws IOException {

        // 입력 방어 — 빈 쿼리는 match_all로 해석하지 않고 400.
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "q parameter is required"));
        }

        int size = sizeParam != null ? Math.min(sizeParam, maxSize) : defaultSize;
        int from = Math.max(page, 0) * size;

        SearchResponse<ObjectNode> resp = handler.search(q, from, size, category, priceMin, priceMax);

        List<Map<String, Object>> hits = resp.hits().hits().stream()
                .map(this::toHitView)
                .collect(Collectors.toList());

        long total = resp.hits().total() != null ? resp.hits().total().value() : 0L;

        Map<String, Object> body = new HashMap<>();
        body.put("total", total);
        body.put("took_ms", resp.took());
        body.put("page", page);
        body.put("size", size);
        body.put("hits", hits);
        body.put("facets", extractFacets(resp));

        log.debug("search q='{}' total={} took={}ms filters=[cat={},p={}~{}]",
                q, total, resp.took(), category, priceMin, priceMax);
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> toHitView(Hit<ObjectNode> h) {
        // Map.of는 null 값을 거부하므로 highlight 유무에 따라 조립.
        Map<String, Object> view = new HashMap<>();
        view.put("id", h.id());
        view.put("score", h.score() != null ? h.score() : 0.0);
        view.put("source", h.source());
        if (h.highlight() != null && !h.highlight().isEmpty()) {
            view.put("highlights", h.highlight());
        }
        return view;
    }

    /**
     * ES aggregation 결과를 프런트가 바로 렌더할 수 있는 flat 구조로 변환.
     *
     * <p>Elasticsearch Java client는 terms/range 결과를 sealed union(Aggregate)로 돌려주므로
     * 분기해서 key/count만 꺼낸다 — 프런트는 ES DSL을 알 필요가 없다.
     */
    private Map<String, Object> extractFacets(SearchResponse<ObjectNode> resp) {
        Map<String, Object> facets = new HashMap<>();

        Aggregate catsAgg = resp.aggregations().get("categories");
        if (catsAgg != null && catsAgg.isSterms()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (StringTermsBucket b : catsAgg.sterms().buckets().array()) {
                items.add(Map.of("key", b.key().stringValue(), "count", b.docCount()));
            }
            facets.put("categories", items);
        } else if (catsAgg != null && catsAgg.isLterms()) {
            // category_leaf가 numeric으로 매핑된 레거시 케이스 대비.
            List<Map<String, Object>> items = new ArrayList<>();
            for (LongTermsBucket b : catsAgg.lterms().buckets().array()) {
                items.add(Map.of("key", String.valueOf(b.key()), "count", b.docCount()));
            }
            facets.put("categories", items);
        }

        Aggregate priceAgg = resp.aggregations().get("price_ranges");
        if (priceAgg != null && priceAgg.isRange()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (RangeBucket b : priceAgg.range().buckets().array()) {
                Map<String, Object> row = new HashMap<>();
                row.put("key", b.key());
                row.put("count", b.docCount());
                if (b.from() != null) row.put("from", b.from());
                if (b.to() != null) row.put("to", b.to());
                items.add(row);
            }
            facets.put("price_ranges", items);
        }

        return facets;
    }
}

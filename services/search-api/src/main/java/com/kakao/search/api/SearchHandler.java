package com.kakao.search.api;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.AggregationRange;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchPhrasePrefixQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.HighlighterType;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ES 쿼리 조립/실행 핵심.
 *
 * <p>쿼리 구조:
 * <pre>
 *   query = bool{
 *     must  = function_score(
 *               multi_match(q, ["title^2", "title.ngram"], best_fields),
 *               [log1p(sales_count) * factor, gauss(updated_at, scale=7d)],
 *               score_mode = SUM, boost_mode = MULTIPLY),
 *     filter = [ term(in_stock=true) ]       // 항상 적용 — facet 계산에도 반영
 *   }
 *   post_filter = bool.filter([ category_leaf=?, range(price,min,max) ])   // 사용자 선택
 *   aggs = {
 *     categories:     terms(category_leaf, size=20),
 *     price_ranges:   range(price, [0-10k, 10k-30k, 30k-100k, 100k+])
 *   }
 * </pre>
 *
 * <p>설계 근거:
 * <ul>
 *   <li><b>title^2 + title.ngram</b>: 정확 매치 우선, edge_ngram은 부분/접두 매치 보조.</li>
 *   <li><b>log1p</b>: sales_count가 10만·100만 단위로 튀어도 폭발하지 않도록 압축.</li>
 *   <li><b>gauss decay</b>: updated_at이 오래될수록 점수 하락, gauss 곡선이라 최근 데이터끼리 차이는 작음.</li>
 *   <li><b>in_stock은 main filter</b>: 재고 없는 상품은 hits/facets 모두에서 제외.</li>
 *   <li><b>category/price는 post_filter</b>: aggregation은 사용자가 필터 걸기 전 모집단을 기준으로
 *       집계해야 다른 카테고리/가격대의 후보 수가 드러난다 — 드릴다운 UX의 핵심.
 *       (단일 post_filter 패턴이므로 엄밀한 per-facet 제외는 하지 않는다 — PoC 단순화.)</li>
 * </ul>
 */
@Component
public class SearchHandler {

    private final ElasticsearchClient es;
    private final String indexName;
    private final double salesBoostFactor;
    private final String freshnessScale;
    private final double freshnessDecay;

    public SearchHandler(
            ElasticsearchClient es,
            @Value("${es.index}") String indexName,
            @Value("${search.sales-boost-factor}") double salesBoostFactor,
            @Value("${search.freshness-scale}") String freshnessScale,
            @Value("${search.freshness-decay}") double freshnessDecay) {
        this.es = es;
        this.indexName = indexName;
        this.salesBoostFactor = salesBoostFactor;
        this.freshnessScale = freshnessScale;
        this.freshnessDecay = freshnessDecay;
    }

    public SearchResponse<ObjectNode> search(
            String q,
            int from,
            int size,
            String categoryLeaf,
            Double priceMin,
            Double priceMax) throws IOException {

        Query textQuery = Query.of(qb -> qb
                .multiMatch(mm -> mm
                        .query(q)
                        .fields("title^2", "title.ngram")
                        .type(TextQueryType.BestFields)));

        FunctionScore salesBoost = FunctionScore.of(fn -> fn
                .fieldValueFactor(fvf -> fvf
                        .field("sales_count")
                        .modifier(FieldValueFactorModifier.Log1p)
                        .factor(salesBoostFactor)
                        .missing(0.0)));

        FunctionScore freshness = FunctionScore.of(fn -> fn
                .gauss(g -> g
                        .field("updated_at")
                        .placement(p -> p
                                .origin(JsonData.of("now"))
                                .scale(JsonData.of(freshnessScale))
                                .decay(freshnessDecay))));

        Query scoredQuery = Query.of(qb -> qb
                .functionScore(fs -> fs
                        .query(textQuery)
                        .functions(List.of(salesBoost, freshness))
                        .scoreMode(FunctionScoreMode.Sum)
                        .boostMode(FunctionBoostMode.Multiply)));

        // 메인 bool: scoredQuery + 항상 적용되는 in_stock 필터.
        Query mainQuery = Query.of(qb -> qb
                .bool(b -> b
                        .must(scoredQuery)
                        .filter(f -> f.term(t -> t
                                .field("in_stock")
                                .value(FieldValue.of(true))))));

        // post_filter: 사용자가 고른 facet 선택. 하나도 없으면 match_all로 두지 않고 null 처리.
        List<Query> postFilterClauses = new ArrayList<>();
        if (categoryLeaf != null && !categoryLeaf.isBlank()) {
            postFilterClauses.add(Query.of(qb -> qb
                    .term(t -> t.field("category_leaf").value(FieldValue.of(categoryLeaf)))));
        }
        if (priceMin != null || priceMax != null) {
            postFilterClauses.add(Query.of(qb -> qb
                    .range(r -> {
                        r.field("price");
                        if (priceMin != null) r.gte(JsonData.of(priceMin));
                        if (priceMax != null) r.lte(JsonData.of(priceMax));
                        return r;
                    })));
        }

        Highlight highlight = Highlight.of(h -> h
                .type(HighlighterType.Unified)
                .preTags("<em>")
                .postTags("</em>")
                .requireFieldMatch(false)
                .fields(Map.of(
                        "title", HighlightField.of(f -> f.numberOfFragments(0))
                )));

        // 가격대 범위는 한국 커머스 관행에 맞는 4단계. unbounded 상한은 [100k, +∞).
        Aggregation categoriesAgg = Aggregation.of(a -> a
                .terms(t -> t.field("category_leaf").size(20)));
        Aggregation priceRangesAgg = Aggregation.of(a -> a
                .range(r -> r
                        .field("price")
                        .ranges(List.of(
                                AggregationRange.of(b -> b.key("~10000").to("10000")),
                                AggregationRange.of(b -> b.key("10000-30000").from("10000").to("30000")),
                                AggregationRange.of(b -> b.key("30000-100000").from("30000").to("100000")),
                                AggregationRange.of(b -> b.key("100000~").from("100000"))
                        ))));

        return es.search(s -> {
                    s.index(indexName)
                     .from(from)
                     .size(size)
                     .trackTotalHits(tt -> tt.enabled(true))
                     .query(mainQuery)
                     .highlight(highlight)
                     .aggregations("categories", categoriesAgg)
                     .aggregations("price_ranges", priceRangesAgg);
                    if (!postFilterClauses.isEmpty()) {
                        s.postFilter(pf -> pf.bool(b -> b.filter(postFilterClauses)));
                    }
                    return s;
                },
                ObjectNode.class);
    }

    /**
     * 자동완성 후보 조회.
     *
     * <p>설계:
     * <ul>
     *   <li><b>match_phrase_prefix</b>: "나이" 같은 짧은 접두 입력에 적합. title과 title.ngram 둘 다 질의.
     *       — title은 공백 기준 토큰의 prefix, title.ngram은 edge_ngram 분석 결과에 대한 prefix.
     *       둘을 bool.should로 묶어 매칭 폭을 넓힌다.</li>
     *   <li><b>sort by sales_count desc</b>: 접두 매칭 후보 중 인기 상품이 먼저 뜨도록.
     *       BM25 점수로도 가능하지만, 자동완성 UX에서는 "많이 팔리는 것"이 더 직관적.</li>
     *   <li><b>_source filter</b>: title + product_id만 반환해 payload 절약.</li>
     *   <li><b>size 10</b>: 드롭다운에 적절한 상한.</li>
     * </ul>
     */
    public SearchResponse<ObjectNode> suggest(String q, int size) throws IOException {
        MatchPhrasePrefixQuery prefixTitle = MatchPhrasePrefixQuery.of(mp -> mp
                .field("title")
                .query(q));
        MatchPhrasePrefixQuery prefixNgram = MatchPhrasePrefixQuery.of(mp -> mp
                .field("title.ngram")
                .query(q));

        return es.search(s -> s
                        .index(indexName)
                        .size(size)
                        .trackTotalHits(tt -> tt.enabled(false))
                        .source(src -> src.filter(f -> f.includes("product_id", "title")))
                        .query(query -> query.bool(b -> b
                                .should(sh -> sh.matchPhrasePrefix(prefixTitle))
                                .should(sh -> sh.matchPhrasePrefix(prefixNgram))
                                .filter(f -> f.term(t -> t
                                        .field("in_stock")
                                        .value(FieldValue.of(true))))
                                .minimumShouldMatch("1")))
                        .sort(so -> so.field(f -> f.field("sales_count").order(SortOrder.Desc))),
                ObjectNode.class);
    }
}

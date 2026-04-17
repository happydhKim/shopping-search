package com.kakao.search.api;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
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
import java.util.List;
import java.util.Map;

/**
 * ES 쿼리 조립/실행 핵심.
 *
 * <p>쿼리 구조 (Phase 3 Turn 3):
 * <pre>
 *   function_score(
 *     query = multi_match(q, ["title^2", "title.ngram"], best_fields),
 *     filter = term(in_stock=true),           // 재고 없는 상품 제외
 *     functions = [
 *       field_value_factor(sales_count, log1p) * salesBoostFactor,   // 판매량 부스팅
 *       gauss(updated_at, origin=now, scale=7d, decay=0.5)           // 신선도 decay
 *     ],
 *     score_mode = SUM,           // 두 함수 점수 합산
 *     boost_mode = MULTIPLY       // BM25 점수에 곱함 → 결합 효과
 *   )
 * </pre>
 *
 * <p>설계 근거:
 * <ul>
 *   <li><b>title^2 + title.ngram</b>: 정확 매치 우선, edge_ngram은 부분/접두 매치 보조.
 *       template의 shopping_edge_ngram(2-10자) 사용.</li>
 *   <li><b>log1p</b>: sales_count가 10만·100만 단위로 튀어도 폭발하지 않도록 압축.</li>
 *   <li><b>gauss decay</b>: updated_at이 오래될수록 점수 하락, 단 linear가 아닌 정규분포
 *       형태라 최근 데이터끼리는 차이가 작음 → 너무 공격적 penalize 방지.</li>
 *   <li><b>filter (not post_filter)</b>: filter는 스코어링 단계 전에 적용되어 품질 좋음.
 *       post_filter는 aggregation 결과를 유지하고 hits만 거를 때 사용.</li>
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

    public SearchResponse<ObjectNode> search(String q, int from, int size) throws IOException {
        Query textQuery = Query.of(qb -> qb
                .multiMatch(mm -> mm
                        .query(q)
                        .fields("title^2", "title.ngram")
                        .type(TextQueryType.BestFields)));

        // 판매량 부스팅 함수 — in_stock=true 문서에만 적용 (재고 없는 상품은 가산점 없음).
        FunctionScore salesBoost = FunctionScore.of(fn -> fn
                .filter(f -> f.term(t -> t
                        .field("in_stock")
                        .value(FieldValue.of(true))))
                .fieldValueFactor(fvf -> fvf
                        .field("sales_count")
                        .modifier(FieldValueFactorModifier.Log1p)
                        .factor(salesBoostFactor)
                        .missing(0.0)));

        // 신선도 decay — updated_at이 오래될수록 점수 하락, gauss 곡선.
        FunctionScore freshness = FunctionScore.of(fn -> fn
                .gauss(g -> g
                        .field("updated_at")
                        .placement(p -> p
                                .origin(JsonData.of("now"))
                                .scale(JsonData.of(freshnessScale))
                                .decay(freshnessDecay))));

        // 매칭 구간 하이라이팅 — title 필드만 대상.
        // - unified highlighter: 기본값, BM25 점수와 정합성 좋고 추가 설정 없이 동작
        // - numberOfFragments(0): title이 짧아 조각내지 않고 전체 반환 → 프론트는 그대로 렌더
        // - requireFieldMatch(false): multi_match가 title.ngram으로 매칭된 경우에도 title 원문에 태그를 씌움
        Highlight highlight = Highlight.of(h -> h
                .type(HighlighterType.Unified)
                .preTags("<em>")
                .postTags("</em>")
                .requireFieldMatch(false)
                .fields(Map.of(
                        "title", HighlightField.of(f -> f.numberOfFragments(0))
                )));

        return es.search(s -> s
                        .index(indexName)
                        .from(from)
                        .size(size)
                        .trackTotalHits(tt -> tt.enabled(true))
                        .query(query -> query
                                .functionScore(fs -> fs
                                        .query(textQuery)
                                        .functions(List.of(salesBoost, freshness))
                                        .scoreMode(FunctionScoreMode.Sum)
                                        .boostMode(FunctionBoostMode.Multiply)
                                ))
                        .highlight(highlight)
                        .postFilter(pf -> pf.term(t -> t
                                .field("in_stock")
                                .value(FieldValue.of(true)))),
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

package com.kakao.search.api;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

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
                        .postFilter(pf -> pf.term(t -> t
                                .field("in_stock")
                                .value(FieldValue.of(true)))),
                ObjectNode.class);
    }
}

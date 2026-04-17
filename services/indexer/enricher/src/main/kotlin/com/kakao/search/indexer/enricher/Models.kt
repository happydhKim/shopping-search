package com.kakao.search.indexer.enricher

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Debezium ExtractNewRecordState SMT 이후 메시지 형태.
 *
 * 원래 Debezium envelope는 { "before": {...}, "after": {...}, "op": "c|u|d", ... } 구조지만
 * unwrap 뒤에는 after 객체가 루트가 되고, delete 이벤트는 __deleted: "true" 필드가 붙는다
 * (connector.json의 delete.handling.mode=rewrite 설정).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ProductRow(
    @JsonProperty("product_id")     val productId: String,
    @JsonProperty("title")          val title: String,
    @JsonProperty("brand_id")       val brandId: String,
    @JsonProperty("category_id")    val categoryId: String,
    @JsonProperty("price")          val price: Int,
    @JsonProperty("original_price") val originalPrice: Int,
    @JsonProperty("stock")          val stock: Int,
    @JsonProperty("sales_count")    val salesCount: Long,
    @JsonProperty("view_count")     val viewCount: Long,
    @JsonProperty("review_count")   val reviewCount: Int,
    @JsonProperty("review_score")   val reviewScore: Double,
    @JsonProperty("tags_json")      val tagsJson: String?,
    @JsonProperty("options_json")   val optionsJson: String?,
    @JsonProperty("seller_id")      val sellerId: String,
    @JsonProperty("shipping_free")  val shippingFree: Int,
    @JsonProperty("image_url")      val imageUrl: String?,
    @JsonProperty("created_at")     val createdAt: Long?,  // Debezium adaptive_time_microseconds → epoch micros
    @JsonProperty("updated_at")     val updatedAt: Long?,
    @JsonProperty("__deleted")      val deleted: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BrandRow(
    @JsonProperty("brand_id")   val brandId: String,
    @JsonProperty("brand_name") val brandName: String,
    @JsonProperty("__deleted")  val deleted: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CategoryRow(
    @JsonProperty("category_id") val categoryId: String,
    @JsonProperty("leaf_name")   val leafName: String,
    @JsonProperty("leaf_path")   val leafPath: String,
    @JsonProperty("__deleted")   val deleted: String? = null,
)

/**
 * ES products-v1 인덱스 스키마와 1:1 매칭.
 * infra/es/templates/products-template.json의 mapping과 필드명/타입이 일치해야 한다.
 * dynamic: strict 이므로 여분 필드가 섞이면 색인 실패.
 */
data class EnrichedProduct(
    val productId: String,
    val title: String,
    val brand: String,
    val brandId: String,
    val categoryPath: List<String>,
    val categoryLeaf: String,
    val tags: List<String>,
    val price: Int,
    val originalPrice: Int,
    val discountRate: Double,
    val currency: String,
    val stock: Int,
    val inStock: Boolean,
    val salesCount: Long,
    val viewCount: Long,
    val reviewCount: Int,
    val reviewScore: Double,
    val options: List<Option>,
    val sellerId: String,
    val shippingFree: Boolean,
    val imageUrl: String?,
    val createdAt: String,   // ISO-8601
    val updatedAt: String,
    val atTimestamp: String, // @timestamp for data stream
) {
    data class Option(val color: String, val size: String, val stock: Int)
}

/**
 * bulk-indexer로 넘기는 wrapper. 삭제 이벤트는 op=DELETE로 표기해
 * 다운스트림에서 ES DELETE API를 호출할 수 있도록 한다.
 */
data class EnrichedMessage(
    val op: Op,
    val productId: String,
    val doc: EnrichedProduct?,   // DELETE면 null
) {
    enum class Op { UPSERT, DELETE }
}

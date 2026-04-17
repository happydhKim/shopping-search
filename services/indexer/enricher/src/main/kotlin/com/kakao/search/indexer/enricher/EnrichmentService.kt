package com.kakao.search.indexer.enricher

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Enrichment 코어.
 *
 * 1) ProductRow → EnrichedProduct 변환 (brand/category lookup + JSON 컬럼 파싱)
 * 2) Brand/Category 변경 이벤트 → 영향받는 products 재발행 (fanout)
 *
 * Redis 키 스키마:
 *   brand:{brand_id}    → brand_name (TTL 1h)
 *   cat:{category_id}   → "leaf_name||leaf_path" (TTL 1h)
 *
 * TTL을 둔 이유: MySQL 장애 시 stale 데이터가 영원히 남지 않도록 하는 safety net.
 * 정합성 자체는 brand/category CDC fanout으로 보장됨.
 */
@Service
class EnrichmentService(
    private val redis: StringRedisTemplate,
    private val jdbc: JdbcTemplate,
    private val kafka: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cacheTtl = Duration.ofHours(1)
    private val outputTopic = "products-enriched"

    fun enrich(row: ProductRow): EnrichedMessage {
        if (row.deleted == "true") {
            return EnrichedMessage(EnrichedMessage.Op.DELETE, row.productId, null)
        }

        val brandName = lookupBrand(row.brandId)
        val (catLeaf, catPath) = lookupCategory(row.categoryId)

        val discountRate = if (row.originalPrice > 0) {
            1.0 - (row.price.toDouble() / row.originalPrice.toDouble())
        } else 0.0

        val tags: List<String> = parseJsonArray(row.tagsJson)
        val options: List<EnrichedProduct.Option> = parseOptions(row.optionsJson)

        val createdIso = microsToIso(row.createdAt)
        val updatedIso = microsToIso(row.updatedAt)

        val doc = EnrichedProduct(
            productId = row.productId,
            title = row.title,
            brand = brandName,
            brandId = row.brandId,
            categoryPath = buildCategoryPaths(catPath),
            categoryLeaf = catLeaf,
            tags = tags,
            price = row.price,
            originalPrice = row.originalPrice,
            discountRate = "%.2f".format(discountRate).toDouble(),
            currency = "KRW",
            stock = row.stock,
            inStock = row.stock > 0,
            salesCount = row.salesCount,
            viewCount = row.viewCount,
            reviewCount = row.reviewCount,
            reviewScore = row.reviewScore,
            options = options,
            sellerId = row.sellerId,
            shippingFree = row.shippingFree == 1,
            imageUrl = row.imageUrl,
            createdAt = createdIso,
            updatedAt = updatedIso,
            atTimestamp = updatedIso,
        )

        return EnrichedMessage(EnrichedMessage.Op.UPSERT, row.productId, doc)
    }

    /**
     * Brand 변경 fanout.
     *
     * 해당 브랜드의 모든 products를 MySQL에서 PK range scan으로 가져와 재발행한다.
     * 프로덕션이라면 stream 방식(Cursor)으로 메모리 누적을 피하고, 청크 단위로 flush해야 안전.
     * PoC에선 단순 IN-memory batch로 처리.
     */
    fun handleBrandChange(row: BrandRow) {
        if (row.deleted == "true") {
            redis.delete("brand:${row.brandId}")
            return
        }
        redis.opsForValue().set("brand:${row.brandId}", row.brandName, cacheTtl)

        val affected = jdbc.queryForList(
            "SELECT product_id FROM products WHERE brand_id = ?",
            String::class.java, row.brandId,
        )
        log.info("brand {} changed → fanout {} products", row.brandId, affected.size)
        affected.forEach { productId ->
            val productRow = fetchProduct(productId) ?: return@forEach
            publish(enrich(productRow))
        }
    }

    fun handleCategoryChange(row: CategoryRow) {
        if (row.deleted == "true") {
            redis.delete("cat:${row.categoryId}")
            return
        }
        redis.opsForValue().set("cat:${row.categoryId}", "${row.leafName}||${row.leafPath}", cacheTtl)

        val affected = jdbc.queryForList(
            "SELECT product_id FROM products WHERE category_id = ?",
            String::class.java, row.categoryId,
        )
        log.info("category {} changed → fanout {} products", row.categoryId, affected.size)
        affected.forEach { productId ->
            val productRow = fetchProduct(productId) ?: return@forEach
            publish(enrich(productRow))
        }
    }

    fun publish(msg: EnrichedMessage) {
        val json = objectMapper.writeValueAsString(msg)
        // key를 productId로 → 같은 상품의 이벤트는 같은 파티션으로 묶여 순서 보장
        kafka.send(outputTopic, msg.productId, json)
    }

    // ─────────── private helpers ───────────

    private fun lookupBrand(brandId: String): String {
        val cached = redis.opsForValue().get("brand:$brandId")
        if (cached != null) return cached
        val name = jdbc.query(
            "SELECT brand_name FROM brands WHERE brand_id = ?",
            { rs, _ -> rs.getString(1) }, brandId,
        ).firstOrNull() ?: "UNKNOWN"
        redis.opsForValue().set("brand:$brandId", name, cacheTtl)
        return name
    }

    private fun lookupCategory(categoryId: String): Pair<String, String> {
        val cached = redis.opsForValue().get("cat:$categoryId")
        if (cached != null) {
            val (leaf, path) = cached.split("||", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            return leaf to path
        }
        val row = jdbc.query(
            "SELECT leaf_name, leaf_path FROM categories WHERE category_id = ?",
            { rs, _ -> rs.getString(1) to rs.getString(2) }, categoryId,
        ).firstOrNull() ?: ("UNKNOWN" to "")
        redis.opsForValue().set("cat:$categoryId", "${row.first}||${row.second}", cacheTtl)
        return row
    }

    private fun fetchProduct(productId: String): ProductRow? {
        return jdbc.query(
            """SELECT product_id, title, brand_id, category_id, price, original_price, stock,
                      sales_count, view_count, review_count, review_score,
                      tags_json, options_json, seller_id, shipping_free, image_url,
                      UNIX_TIMESTAMP(created_at)*1000000 AS created_at,
                      UNIX_TIMESTAMP(updated_at)*1000000 AS updated_at
               FROM products WHERE product_id = ?""",
            { rs, _ ->
                ProductRow(
                    productId = rs.getString("product_id"),
                    title = rs.getString("title"),
                    brandId = rs.getString("brand_id"),
                    categoryId = rs.getString("category_id"),
                    price = rs.getInt("price"),
                    originalPrice = rs.getInt("original_price"),
                    stock = rs.getInt("stock"),
                    salesCount = rs.getLong("sales_count"),
                    viewCount = rs.getLong("view_count"),
                    reviewCount = rs.getInt("review_count"),
                    reviewScore = rs.getDouble("review_score"),
                    tagsJson = rs.getString("tags_json"),
                    optionsJson = rs.getString("options_json"),
                    sellerId = rs.getString("seller_id"),
                    shippingFree = rs.getInt("shipping_free"),
                    imageUrl = rs.getString("image_url"),
                    createdAt = rs.getLong("created_at"),
                    updatedAt = rs.getLong("updated_at"),
                )
            },
            productId,
        ).firstOrNull()
    }

    private fun parseJsonArray(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            objectMapper.readValue(json, object : TypeReference<List<String>>() {})
        }.getOrElse { emptyList() }
    }

    private fun parseOptions(json: String?): List<EnrichedProduct.Option> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val raw: List<Map<String, Any>> = objectMapper.readValue(
                json, object : TypeReference<List<Map<String, Any>>>() {},
            )
            raw.map {
                EnrichedProduct.Option(
                    color = it["color"]?.toString() ?: "",
                    size = it["size"]?.toString() ?: "",
                    stock = (it["stock"] as? Number)?.toInt() ?: 0,
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun buildCategoryPaths(leafPath: String): List<String> {
        if (leafPath.isBlank()) return emptyList()
        val parts = leafPath.split("/")
        return (1..parts.size).map { parts.take(it).joinToString("/") }
    }

    /**
     * Debezium adaptive_time_microseconds → ISO-8601.
     * epoch micros 기준. null 이면 현재 시각으로 대체 (스냅샷 중 타이밍 이슈 방지).
     */
    private fun microsToIso(micros: Long?): String {
        val instant = if (micros != null && micros > 0) {
            Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1000)
        } else Instant.now()
        return instant.atOffset(ZoneOffset.UTC).toString()
    }
}

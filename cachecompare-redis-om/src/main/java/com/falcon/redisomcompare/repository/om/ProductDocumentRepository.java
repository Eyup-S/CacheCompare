package com.falcon.redisomcompare.repository.om;

import com.falcon.redisomcompare.entity.ProductDocument;
import com.redis.om.spring.repository.RedisDocumentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Redis OM Spring repository.
 *
 * Index alanları (FT.CREATE ile):
 *   category → TAG field  (@Indexed String)
 *   brand    → TAG field  (@Indexed String)
 *   stock    → NUMERIC field (@Indexed Integer)
 *
 * NOT: BigDecimal price @Indexed yapılmadı — redis-om-spring 0.9.6'da
 * BigDecimal NUMERIC alan olarak işlenemiyor ve tüm index'i bozuyor.
 * Price filtresi 4-field sorguda Java katmanında uygulanır.
 */
public interface ProductDocumentRepository extends RedisDocumentRepository<ProductDocument, String> {

    /**
     * 2-field: category + brand
     * FT.SEARCH idx:ProductDocument @category:{cat} @brand:{brand}
     */
    Page<ProductDocument> findByCategoryAndBrand(String category, String brand, Pageable pageable);

    /**
     * 4-field (kısmi): category + brand + stock aralığı
     * FT.SEARCH @category:{cat} @brand:{brand} @stock:[min max]
     * Price filtresi Java'da uygulanır (price indexed değil).
     */
    Page<ProductDocument> findByCategoryAndBrandAndStockBetween(
            String category, String brand,
            Integer stockMin, Integer stockMax,
            Pageable pageable);
}

package com.falcon.redisomcompare.search;

import com.falcon.redisomcompare.entity.Product;

import java.math.BigDecimal;
import java.util.List;

/**
 * Alan bazlı arama stratejisi.
 *
 * İki implementasyon:
 *  - RedisOmSearchStrategy  (redis-om profil)  → RediSearch FT.SEARCH
 *  - RedisHashSearchStrategy (redis-template profil) → SINTER + Java filtresi
 */
public interface SearchStrategy {

    /**
     * 2-field exact-match: category + brand.
     * Redis OM: FT.SEARCH @category:{cat} @brand:{brand}
     * RedisHash: SINTER ph:category:{cat} ph:brand:{brand} → HGETALL per ID
     */
    List<Product> searchTwoFields(String category, String brand);

    /**
     * 4-field: category + brand + price aralığı + stock aralığı.
     * Redis OM: FT.SEARCH @category:{cat} @brand:{brand} @price:[min max] @stock:[min max]
     * RedisHash: SINTER ph:category:{cat} ph:brand:{brand} → Java filtresi (price, stock)
     */
    List<Product> searchFourFields(String category, String brand,
                                   BigDecimal priceMin, BigDecimal priceMax,
                                   int stockMin, int stockMax);

    /** Veriyi search store'a yükle (benchmark öncesi çağrılır). */
    void loadData(List<Product> products);

    String providerName();
}

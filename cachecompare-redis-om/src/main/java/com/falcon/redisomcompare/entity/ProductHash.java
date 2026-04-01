package com.falcon.redisomcompare.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.index.Indexed;

/**
 * Spring Data Redis @RedisHash entity.
 * Redis'te Hash (HSET) olarak saklanır; key formatı: ph:{id}
 *
 * @Indexed alanlar için Set bazlı ikincil index oluşturulur:
 *   ph:category:Electronics  → {id1, id2, ...}
 *   ph:brand:Brand3          → {id1, id3, ...}
 *
 * 2-field sorgu: SINTER ph:category:Electronics ph:brand:Brand3 → ID listesi
 * → Her ID için HGETALL ph:{id} — sonuçlar birleştirilir.
 *
 * Kısıtlama: sadece exact-match desteklenir.
 * price/stock aralık filtresi uygulama katmanında (Java'da) yapılır.
 *
 * price: double olarak saklanır (BigDecimal Redis Hash'te sorunsuz yazılmaz).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RedisHash("ph")
public class ProductHash {

    @Id
    private Long id;

    private String name;

    @Indexed
    private String category;

    @Indexed
    private String brand;

    /** price double olarak tutulur — range sorgu Java filtresiyle yapılır */
    private Double price;

    /** stock filtresi Java'da yapılır */
    private Integer stock;

    private String sku;
    private Double weight;
}

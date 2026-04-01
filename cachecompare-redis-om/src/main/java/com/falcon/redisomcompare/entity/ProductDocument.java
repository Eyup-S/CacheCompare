package com.falcon.redisomcompare.entity;

import com.redis.om.spring.annotations.Document;
import com.redis.om.spring.annotations.Indexed;
import lombok.*;
import org.springframework.data.annotation.Id;

import java.math.BigDecimal;

/**
 * Redis OM Spring @Document entity.
 * Redis Stack'te JSON formatında saklanır.
 * Key formatı: ProductDocument:{id}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document
public class ProductDocument {

    @Id
    private String id;   // String olarak tutuluyor; Product.id'ye karşılık gelir

    private String name;
    private String description;
    private BigDecimal price;          // @Indexed değil — BigDecimal RediSearch NUMERIC'te sorun çıkarır
    @Indexed private Integer stock;    // Integer → NUMERIC field (çalışır)
    @Indexed private String category;  // String  → TAG field
    @Indexed private String brand;     // String  → TAG field
    private String sku;
    private Double weight;
    private String imageUrl;

    // ── Dönüşüm yardımcıları ────────────────────────────────────────────

    public static ProductDocument from(Long id, Product product) {
        return ProductDocument.builder()
                .id(String.valueOf(id))
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .stock(product.getStock())
                .category(product.getCategory())
                .brand(product.getBrand())
                .sku(product.getSku())
                .weight(product.getWeight())
                .imageUrl(product.getImageUrl())
                .build();
    }

    public Product toProduct() {
        return Product.builder()
                .id(Long.parseLong(this.id))
                .name(this.name)
                .description(this.description)
                .price(this.price)
                .stock(this.stock)
                .category(this.category)
                .brand(this.brand)
                .sku(this.sku)
                .weight(this.weight)
                .imageUrl(this.imageUrl)
                .build();
    }
}

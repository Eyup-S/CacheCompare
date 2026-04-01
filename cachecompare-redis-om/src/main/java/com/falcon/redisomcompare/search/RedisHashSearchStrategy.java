package com.falcon.redisomcompare.search;

import com.falcon.redisomcompare.entity.Product;
import com.falcon.redisomcompare.entity.ProductHash;
import com.falcon.redisomcompare.repository.redis.ProductHashRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.StreamSupport;

/**
 * Spring Data Redis @RedisHash + @Indexed arama stratejisi.
 *
 * 2-field sorgu mekanizması:
 *   SINTER ph:category:{cat} ph:brand:{brand} → eşleşen ID seti
 *   Her ID için: HGETALL ph:{id}
 *   O(|sonuç| × Redis round-trip) — ağ gecikmesi birikir
 *
 * 4-field sorgu mekanizması:
 *   2-field ile aynı SINTER + HGETALL
 *   price ve stock aralık filtresi Java'da stream().filter() ile uygulanır
 *   (spring-data-redis @Indexed yalnızca exact-match destekler; numeric range yoktur)
 *
 * Kısıtlama: büyük dataset'te SINTER + N×HGETALL = N Redis komut round-trip
 * Bu senaryo özellikle 200k kayıtta RediSearch ile dramatik fark oluşturur.
 */
@Service
@Profile("redis-template")
@RequiredArgsConstructor
public class RedisHashSearchStrategy implements SearchStrategy {

    private final ProductHashRepository productHashRepository;

    @Override
    public List<Product> searchTwoFields(String category, String brand) {
        return productHashRepository.findByCategoryAndBrand(category, brand)
                .stream()
                .map(this::toProduct)
                .toList();
    }

    @Override
    public List<Product> searchFourFields(String category, String brand,
                                          BigDecimal priceMin, BigDecimal priceMax,
                                          int stockMin, int stockMax) {
        double pMin = priceMin.doubleValue();
        double pMax = priceMax.doubleValue();

        return productHashRepository.findByCategoryAndBrand(category, brand)
                .stream()
                .filter(h -> h.getPrice() != null
                        && h.getPrice() >= pMin
                        && h.getPrice() <= pMax)
                .filter(h -> h.getStock() != null
                        && h.getStock() >= stockMin
                        && h.getStock() <= stockMax)
                .map(this::toProduct)
                .toList();
    }

    @Override
    public void loadData(List<Product> products) {
        List<ProductHash> hashes = products.stream()
                .map(p -> ProductHash.builder()
                        .id(p.getId())
                        .name(p.getName())
                        .category(p.getCategory())
                        .brand(p.getBrand())
                        .price(p.getPrice() != null ? p.getPrice().doubleValue() : null)
                        .stock(p.getStock())
                        .sku(p.getSku())
                        .weight(p.getWeight())
                        .build())
                .toList();
        productHashRepository.saveAll(hashes);
    }

    private Product toProduct(ProductHash h) {
        return Product.builder()
                .id(h.getId())
                .name(h.getName())
                .category(h.getCategory())
                .brand(h.getBrand())
                .price(h.getPrice() != null ? BigDecimal.valueOf(h.getPrice()) : null)
                .stock(h.getStock())
                .sku(h.getSku())
                .weight(h.getWeight())
                .build();
    }

    @Override
    public String providerName() {
        return "redis-template";
    }
}

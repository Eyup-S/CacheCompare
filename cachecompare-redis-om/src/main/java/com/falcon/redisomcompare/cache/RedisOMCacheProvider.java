package com.falcon.redisomcompare.cache;

import com.falcon.redisomcompare.entity.Product;
import com.falcon.redisomcompare.entity.ProductDocument;
import com.falcon.redisomcompare.repository.om.ProductDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Redis OM for Spring ile cache sağlayıcısı.
 * RedisDocumentRepository üzerinden JSON.GET/SET işlemleri (RedisJSON modülü).
 * RedisTemplate tabanlı yaklaşıma kıyasla repository soyutlama katmanı ekler.
 */
@Service
@Profile("redis-om")
@RequiredArgsConstructor
public class RedisOMCacheProvider implements CacheProvider {

    private final ProductDocumentRepository productDocumentRepository;

    @Override
    public Optional<Product> get(Long id) {
        return productDocumentRepository.findById(String.valueOf(id))
                .map(ProductDocument::toProduct);
    }

    @Override
    public void put(Long id, Product product) {
        productDocumentRepository.save(ProductDocument.from(id, product));
    }

    @Override
    public void putAll(Map<Long, Product> products) {
        List<ProductDocument> docs = products.entrySet().stream()
                .map(e -> ProductDocument.from(e.getKey(), e.getValue()))
                .toList();
        productDocumentRepository.saveAll(docs);
    }

    @Override
    public void evict(Long id) {
        productDocumentRepository.deleteById(String.valueOf(id));
    }

    @Override
    public String providerName() {
        return "redis-om";
    }
}

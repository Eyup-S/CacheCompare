package com.falcon.redisomcompare.cache;

import com.falcon.redisomcompare.entity.Product;

import java.util.Map;
import java.util.Optional;

public interface CacheProvider {

    Optional<Product> get(Long id);

    void put(Long id, Product product);

    void putAll(Map<Long, Product> products);

    void evict(Long id);

    String providerName();
}

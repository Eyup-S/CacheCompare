package com.falcon.cachecompare.cache;

import com.falcon.cachecompare.entity.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
@Profile("inmemory")
@RequiredArgsConstructor
public class InMemoryCacheProvider implements CacheProvider {

    private final Map<Long, Product> productCache;

    @Override
    public Optional<Product> get(Long id) {
        return Optional.ofNullable(productCache.get(id));
    }

    @Override
    public void put(Long id, Product product) {
        productCache.put(id, product);
    }

    @Override
    public void putAll(Map<Long, Product> products) {
        productCache.putAll(products);
    }

    @Override
    public void evict(Long id) {
        productCache.remove(id);
    }

    @Override
    public String providerName() {
        return "inmemory";
    }
}

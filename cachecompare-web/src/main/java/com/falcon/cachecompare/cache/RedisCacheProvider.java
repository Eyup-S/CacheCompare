package com.falcon.cachecompare.cache;

import com.falcon.cachecompare.entity.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Profile("redis")
@RequiredArgsConstructor
public class RedisCacheProvider implements CacheProvider {

    private static final String KEY_PREFIX = "product:";

    private final RedisTemplate<String, Product> redisTemplate;

    @Override
    public Optional<Product> get(Long id) {
        Product product = redisTemplate.opsForValue().get(KEY_PREFIX + id);
        return Optional.ofNullable(product);
    }

    @Override
    public void put(Long id, Product product) {
        redisTemplate.opsForValue().set(KEY_PREFIX + id, product);
    }

    @Override
    public void putAll(Map<Long, Product> products) {
        Map<String, Product> stringKeyed = new HashMap<>();
        products.forEach((k, v) -> stringKeyed.put(KEY_PREFIX + k, v));
        redisTemplate.opsForValue().multiSet(stringKeyed);
    }

    @Override
    public void evict(Long id) {
        redisTemplate.delete(KEY_PREFIX + id);
    }

    @Override
    public String providerName() {
        return "redis";
    }
}

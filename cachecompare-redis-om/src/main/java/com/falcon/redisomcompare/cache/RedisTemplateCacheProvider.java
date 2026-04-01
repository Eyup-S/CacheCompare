package com.falcon.redisomcompare.cache;

import com.falcon.redisomcompare.entity.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Standart Spring RedisTemplate ile cache sağlayıcısı.
 * Jackson2JsonRedisSerializer ile Product → JSON → Redis SET/GET yapar.
 */
@Service
@Profile("redis-template")
@RequiredArgsConstructor
public class RedisTemplateCacheProvider implements CacheProvider {

    private static final String KEY_PREFIX = "rt:product:";

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
        Map<String, Product> stringKeyed = new HashMap<>(products.size());
        products.forEach((k, v) -> stringKeyed.put(KEY_PREFIX + k, v));
        redisTemplate.opsForValue().multiSet(stringKeyed);
    }

    @Override
    public void evict(Long id) {
        redisTemplate.delete(KEY_PREFIX + id);
    }

    @Override
    public String providerName() {
        return "redis-template";
    }
}

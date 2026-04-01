package com.falcon.redisomcompare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RedisTemplate vs Redis OM for Spring benchmark uygulaması.
 *
 * Profiller:
 *   --spring.profiles.active=redis-template  → Spring RedisTemplate (JSON serileştirme)
 *   --spring.profiles.active=redis-om        → Redis OM for Spring (RedisJSON / JSON.GET)
 *
 * Strateji:
 *   --cache.strategy=lazy   → Cache-aside (ilk istekte DB'den çek)
 *   --cache.strategy=eager  → Tüm veriyi startup'ta cache'e yükle
 *
 * Dataset boyutu:
 *   --dataset.size=1000     → 1k kayıt (ID aralığı: 1-1000)
 *   --dataset.size=200000   → 200k kayıt (ID aralığı: 1-200000)
 *
 * Redis Stack (port 6380) gereklidir: docker-compose üzerinden cachecompare-redis-stack
 */
@SpringBootApplication
public class RedisOmCompareApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedisOmCompareApplication.class, args);
    }
}

package com.falcon.redisomcompare.config;

import com.redis.om.spring.annotations.EnableRedisDocumentRepositories;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Redis OM for Spring konfigürasyonu.
 * Sadece "redis-om" profili aktifken yüklenir.
 * @EnableRedisDocumentRepositories → ProductDocumentRepository bean'ini oluşturur.
 * Redis Stack (RedisJSON modülü) gerektirir — port 6380.
 */
@Configuration
@Profile("redis-om")
@EnableRedisDocumentRepositories(basePackages = {
        "com.falcon.redisomcompare.repository.om",
        "com.falcon.redisomcompare.entity"          // @Document entity'leri tara → FT.CREATE
})
public class RedisOMConfig {
    // Redis OM Spring kendi auto-configuration'ını çalıştırır.
    // Bağlantı ayarları application.yml redis-om profilinden gelir.
}

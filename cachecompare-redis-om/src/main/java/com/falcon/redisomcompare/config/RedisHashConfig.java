package com.falcon.redisomcompare.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;

/**
 * Spring Data Redis @RedisHash repository altyapısını etkinleştirir.
 * Sadece "redis-template" profili aktifken yüklenir.
 *
 * @EnableRedisRepositories: ph:* keyspace altındaki @RedisHash entity'leri
 * ve @Indexed set bazlı ikincil indexleri yönetir.
 *
 * global spring.data.redis.repositories.enabled=false ayarını geçersiz kılar
 * (sadece belirtilen paket için, explicit olarak etkinleştirilir).
 */
@Configuration
@Profile("redis-template")
@EnableRedisRepositories(basePackages = "com.falcon.redisomcompare.repository.redis")
public class RedisHashConfig {
}

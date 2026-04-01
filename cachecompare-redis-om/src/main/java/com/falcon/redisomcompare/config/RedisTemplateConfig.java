package com.falcon.redisomcompare.config;

import com.falcon.redisomcompare.entity.Product;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * RedisTemplate tabanlı provider konfigürasyonu.
 * Standart Spring Data Redis — JSON serileştirme ile key-value saklama.
 */
@Configuration
@Profile("redis-template")
public class RedisTemplateConfig {

    @Bean
    public RedisTemplate<String, Product> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Product> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());

        Jackson2JsonRedisSerializer<Product> valueSerializer =
                new Jackson2JsonRedisSerializer<>(Product.class);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        return template;
    }
}

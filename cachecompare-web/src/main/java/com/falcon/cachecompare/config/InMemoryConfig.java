package com.falcon.cachecompare.config;

import com.falcon.cachecompare.entity.Product;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@Profile("inmemory")
public class InMemoryConfig {

    @Bean
    public Map<Long, Product> productCache() {
        return new ConcurrentHashMap<>(1024);
    }
}

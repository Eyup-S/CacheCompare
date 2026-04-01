package com.falcon.redisomcompare.strategy;

import com.falcon.redisomcompare.entity.Product;

import java.util.List;

public interface CacheStrategy {

    Product getProduct(Long id);

    void warmUp(List<Product> products);

    String strategyName();

    WarmupStatus getWarmupStatus();
}

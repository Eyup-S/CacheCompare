package com.falcon.cachecompare.strategy;

import com.falcon.cachecompare.entity.Product;

import java.util.List;

public interface CacheStrategy {

    Product getProduct(Long id);

    void warmUp(List<Product> products);

    String strategyName();
}

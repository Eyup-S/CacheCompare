package com.falcon.cachecompare.repository;

import com.falcon.cachecompare.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}

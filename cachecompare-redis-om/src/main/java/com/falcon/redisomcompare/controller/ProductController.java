package com.falcon.redisomcompare.controller;

import com.falcon.redisomcompare.entity.Product;
import com.falcon.redisomcompare.service.BulkBenchmarkResult;
import com.falcon.redisomcompare.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping("/{id}")
    public ResponseEntity<Product> getById(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getProduct(id));
    }

    @GetMapping("/benchmark/bulk")
    public ResponseEntity<BulkBenchmarkResult> bulkBenchmark(
            @RequestParam(defaultValue = "1000") int count) {
        return ResponseEntity.ok(productService.runBulkBenchmark(count));
    }
}

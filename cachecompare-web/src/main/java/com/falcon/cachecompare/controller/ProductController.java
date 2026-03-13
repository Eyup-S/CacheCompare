package com.falcon.cachecompare.controller;

import com.falcon.cachecompare.entity.Product;
import com.falcon.cachecompare.service.BulkBenchmarkResult;
import com.falcon.cachecompare.service.ProductService;
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

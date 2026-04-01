package com.falcon.redisomcompare.controller;

import com.falcon.redisomcompare.strategy.CacheStrategy;
import com.falcon.redisomcompare.strategy.WarmupStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Warm-up ilerleme endpoint'i.
 *
 * GET /warmup/status
 *   → {"ready":false,"loaded":45000,"total":200000,"progressPct":22,...}
 *   → {"ready":true, "loaded":200000,"total":200000,"progressPct":100,...}
 *
 * Benchmark script'i eager stratejide bu endpoint'i
 * ready=true dönene kadar polling yapar.
 */
@RestController
@RequiredArgsConstructor
public class WarmupController {

    private final CacheStrategy cacheStrategy;

    @GetMapping("/warmup/status")
    public ResponseEntity<WarmupStatus> status() {
        return ResponseEntity.ok(cacheStrategy.getWarmupStatus());
    }
}

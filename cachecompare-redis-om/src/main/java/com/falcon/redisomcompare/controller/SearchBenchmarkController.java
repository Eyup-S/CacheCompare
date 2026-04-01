package com.falcon.redisomcompare.controller;

import com.falcon.redisomcompare.search.SearchBenchmarkResult;
import com.falcon.redisomcompare.service.SearchBenchmarkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Alan bazlı arama benchmark endpoint'leri.
 *
 * POST /search/load
 *   PostgreSQL'den veri çekip search store'a yükler.
 *   redis-template: ProductHash (ph:* keyspace, HSET + SADD index)
 *   redis-om:       ProductDocument (JSON.SET + FT.SEARCH index)
 *
 * GET /search/benchmark?type=2field&count=500
 *   N adet rastgele 2-field veya 4-field sorgu çalıştırır, metrikleri döner.
 */
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchBenchmarkController {

    private final SearchBenchmarkService searchBenchmarkService;

    @PostMapping("/load")
    public ResponseEntity<Map<String, Object>> load() {
        int loaded = searchBenchmarkService.loadSearchData();
        return ResponseEntity.ok(Map.of(
                "loaded", loaded,
                "provider", searchBenchmarkService.getClass().getSimpleName()
        ));
    }

    @GetMapping("/benchmark")
    public ResponseEntity<SearchBenchmarkResult> benchmark(
            @RequestParam(defaultValue = "2field") String type,
            @RequestParam(defaultValue = "500") int count) {
        SearchBenchmarkResult result = "4field".equals(type)
                ? searchBenchmarkService.runFourFieldBenchmark(count)
                : searchBenchmarkService.runTwoFieldBenchmark(count);
        return ResponseEntity.ok(result);
    }
}

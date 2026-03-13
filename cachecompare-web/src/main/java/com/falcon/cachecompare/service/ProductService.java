package com.falcon.cachecompare.service;

import com.falcon.cachecompare.entity.Product;
import com.falcon.cachecompare.exception.ProductNotFoundException;
import com.falcon.cachecompare.strategy.CacheStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final CacheStrategy cacheStrategy;

    private final Random random = new Random();

    public Product getProduct(Long id) {
        return cacheStrategy.getProduct(id);
    }

    public BulkBenchmarkResult runBulkBenchmark(int count) {
        List<Long> ids = generateRandomIds(count);

        long totalStart = System.nanoTime();
        long hits = 0;
        long misses = 0;
        long minNs = Long.MAX_VALUE;
        long maxNs = 0;
        long totalNs = 0;

        for (Long id : ids) {
            long start = System.nanoTime();
            try {
                cacheStrategy.getProduct(id);
                hits++;
            } catch (ProductNotFoundException e) {
                misses++;
            }
            long elapsed = System.nanoTime() - start;
            totalNs += elapsed;
            if (elapsed < minNs) minNs = elapsed;
            if (elapsed > maxNs) maxNs = elapsed;
        }

        long wallNs = System.nanoTime() - totalStart;
        long avgNs = count > 0 ? totalNs / count : 0;

        return BulkBenchmarkResult.builder()
                .count(count)
                .hits(hits)
                .misses(misses)
                .totalWallMs(wallNs / 1_000_000)
                .avgNs(avgNs)
                .minNs(minNs == Long.MAX_VALUE ? 0 : minNs)
                .maxNs(maxNs)
                .avgMs(avgNs / 1_000_000.0)
                .minMs(minNs == Long.MAX_VALUE ? 0 : minNs / 1_000_000.0)
                .maxMs(maxNs / 1_000_000.0)
                .build();
    }

    private List<Long> generateRandomIds(int count) {
        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add((long) (random.nextInt(1000) + 1));
        }
        return ids;
    }
}

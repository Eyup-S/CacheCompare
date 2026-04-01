package com.falcon.redisomcompare.service;

import com.falcon.redisomcompare.entity.Product;
import com.falcon.redisomcompare.exception.ProductNotFoundException;
import com.falcon.redisomcompare.strategy.CacheStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final CacheStrategy cacheStrategy;

    @Value("${dataset.size:1000}")
    private int datasetSize;

    private final Random random = new Random();

    public Product getProduct(Long id) {
        return cacheStrategy.getProduct(id);
    }

    /**
     * Bulk benchmark: count adet rastgele ürün isteği atar ve metrikleri döner.
     * Rastgele ID'ler [1, datasetSize] aralığında üretilir.
     */
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
                .datasetSize(datasetSize)
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
            ids.add((long) (random.nextInt(datasetSize) + 1));
        }
        return ids;
    }
}

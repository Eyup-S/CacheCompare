package com.falcon.redisomcompare.service;

import com.falcon.redisomcompare.entity.Product;
import com.falcon.redisomcompare.repository.ProductRepository;
import com.falcon.redisomcompare.search.SearchBenchmarkResult;
import com.falcon.redisomcompare.search.SearchStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class SearchBenchmarkService {

    private final SearchStrategy searchStrategy;
    private final ProductRepository productRepository;

    @Value("${dataset.size:1000}")
    private int datasetSize;

    private static final String[] CATEGORIES =
            {"Electronics", "Clothing", "Books", "Sports", "Home", "Toys", "Food", "Auto"};

    private final Random random = new Random();

    /**
     * PostgreSQL'den tüm ürünleri çekip search store'a yükler.
     * redis-om  → ProductDocumentRepository (zaten eager warm-up'ta yüklendi ise atlanabilir)
     * redis-template → ProductHashRepository (her zaman explicit yükleme gerekir)
     */
    public int loadSearchData() {
        List<Product> all = productRepository.findAll();
        // datasetSize kadarını yükle — PostgreSQL'de daha fazla kayıt olsa bile
        List<Product> limited = all.size() > datasetSize ? all.subList(0, datasetSize) : all;
        searchStrategy.loadData(limited);
        return limited.size();
    }

    /** 2-field (category + brand) benchmark — N sorgu */
    public SearchBenchmarkResult runTwoFieldBenchmark(int count) {
        return run(count, "2field");
    }

    /** 4-field (category + brand + price range + stock range) benchmark — N sorgu */
    public SearchBenchmarkResult runFourFieldBenchmark(int count) {
        return run(count, "4field");
    }

    private SearchBenchmarkResult run(int count, String type) {
        List<Double> durations = new ArrayList<>(count);
        long totalResults = 0;

        long wallStart = System.nanoTime();

        for (int i = 0; i < count; i++) {
            String category = CATEGORIES[random.nextInt(CATEGORIES.length)];
            String brand    = "Brand" + (random.nextInt(20) + 1);

            List<Product> results;
            long start = System.nanoTime();

            if ("2field".equals(type)) {
                results = searchStrategy.searchTwoFields(category, brand);
            } else {
                // price aralığı: dataset boyutunun %5'i genişliğinde, rastgele başlangıç
                double maxPrice   = 10.0 + datasetSize;
                double rangeWidth = Math.max(200.0, maxPrice * 0.05);
                double pMinD      = 10.0 + random.nextDouble() * (maxPrice - rangeWidth - 10.0);
                BigDecimal pMin   = BigDecimal.valueOf(pMinD).setScale(2, RoundingMode.HALF_UP);
                BigDecimal pMax   = pMin.add(BigDecimal.valueOf(rangeWidth));

                // stock: 100-149 aralığından 15 genişliğinde rastgele pencere (~30%)
                int sMin = 100 + random.nextInt(35);
                int sMax = sMin + 14;

                results = searchStrategy.searchFourFields(category, brand, pMin, pMax, sMin, sMax);
            }

            double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
            durations.add(elapsedMs);
            totalResults += results.size();
        }

        long wallMs = (System.nanoTime() - wallStart) / 1_000_000;

        List<Double> sorted = new ArrayList<>(durations);
        Collections.sort(sorted);

        double avg = sorted.stream().mapToDouble(d -> d).average().orElse(0.0);

        return SearchBenchmarkResult.builder()
                .provider(searchStrategy.providerName())
                .queryType(type)
                .datasetSize(datasetSize)
                .queryCount(count)
                .totalResultCount(totalResults)
                .avgResultCount((double) totalResults / count)
                .avgMs(avg)
                .minMs(sorted.isEmpty() ? 0 : sorted.get(0))
                .maxMs(sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1))
                .p50Ms(percentile(sorted, 50))
                .p90Ms(percentile(sorted, 90))
                .p99Ms(percentile(sorted, 99))
                .totalWallMs(wallMs)
                .build();
    }

    private double percentile(List<Double> sorted, int p) {
        if (sorted.isEmpty()) return 0.0;
        double idx = (sorted.size() - 1) * p / 100.0;
        int lo = (int) idx;
        int hi = Math.min(lo + 1, sorted.size() - 1);
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (idx - lo);
    }
}

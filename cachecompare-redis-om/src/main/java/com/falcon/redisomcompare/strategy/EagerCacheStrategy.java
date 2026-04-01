package com.falcon.redisomcompare.strategy;

import com.falcon.redisomcompare.cache.CacheProvider;
import com.falcon.redisomcompare.entity.Product;
import com.falcon.redisomcompare.exception.ProductNotFoundException;
import com.falcon.redisomcompare.logging.CacheLogger;
import com.falcon.redisomcompare.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Eager (önceden ısınma) stratejisi.
 * Tüm veriyi uygulama başlarken cache'e yükler.
 * Warm-up ilerlemesi WarmupStatus üzerinden izlenebilir.
 */
@Service
@ConditionalOnProperty(name = "cache.strategy", havingValue = "eager")
@RequiredArgsConstructor
public class EagerCacheStrategy implements CacheStrategy, ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(EagerCacheStrategy.class);

    /** Her batch'te kaç kayıt cache'e yazılacak. */
    private static final int BATCH_SIZE = 500;

    private final CacheProvider cacheProvider;
    private final ProductRepository productRepository;
    private final CacheLogger cacheLogger;

    private final AtomicBoolean warmupComplete = new AtomicBoolean(false);
    private final AtomicInteger loadedCount   = new AtomicInteger(0);
    private volatile int totalCount = 0;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("[EAGER] Cache ısınması başlıyor (provider={})...", cacheProvider.providerName());
        List<Product> all = productRepository.findAll();
        warmUp(all);
        log.info("[EAGER] Cache ısınması tamamlandı. {}/{} ürün '{}' cache'ine yüklendi.",
                loadedCount.get(), totalCount, cacheProvider.providerName());
    }

    @Override
    public void warmUp(List<Product> products) {
        totalCount = products.size();
        loadedCount.set(0);
        warmupComplete.set(false);

        long warmupStart = System.nanoTime();
        int size = products.size();

        // Batch'ler halinde yükle → hem progress takibi hem bellek dostu
        for (int i = 0; i < size; i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, size);
            List<Product> batch = products.subList(i, end);

            Map<Long, Product> batchMap = new HashMap<>(batch.size());
            for (Product p : batch) {
                batchMap.put(p.getId(), p);
            }

            long batchStart = System.nanoTime();
            cacheProvider.putAll(batchMap);
            long batchElapsed = System.nanoTime() - batchStart;

            int loaded = loadedCount.addAndGet(batch.size());
            long perItemNs = batch.isEmpty() ? 0 : batchElapsed / batch.size();

            for (Product p : batch) {
                cacheLogger.log(p.getId(), "WARM_UP", perItemNs,
                        cacheProvider.providerName(), strategyName());
            }

            if (loaded % 10_000 == 0 || loaded == size) {
                log.info("[EAGER] Warm-up ilerlemesi: {}/{} (%{})",
                        loaded, size, (int)(loaded * 100L / size));
            }
        }

        warmupComplete.set(true);
        long totalMs = (System.nanoTime() - warmupStart) / 1_000_000;
        log.info("[EAGER] Warm-up tamamlandı: {} kayıt, {}ms", size, totalMs);
    }

    @Override
    public Product getProduct(Long id) {
        long start = System.nanoTime();
        Product product = cacheProvider.get(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        long elapsed = System.nanoTime() - start;
        cacheLogger.log(id, "HIT", elapsed, cacheProvider.providerName(), strategyName());
        return product;
    }

    @Override
    public WarmupStatus getWarmupStatus() {
        return WarmupStatus.builder()
                .ready(warmupComplete.get())
                .loaded(loadedCount.get())
                .total(totalCount)
                .strategy(strategyName())
                .provider(cacheProvider.providerName())
                .build();
    }

    @Override
    public String strategyName() {
        return "eager";
    }
}

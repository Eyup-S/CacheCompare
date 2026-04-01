package com.falcon.redisomcompare.strategy;

import com.falcon.redisomcompare.cache.CacheProvider;
import com.falcon.redisomcompare.entity.Product;
import com.falcon.redisomcompare.exception.ProductNotFoundException;
import com.falcon.redisomcompare.logging.CacheLogger;
import com.falcon.redisomcompare.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Lazy (cache-aside) stratejisi.
 * İlk istekte DB'den çeker ve cache'e yazar; sonraki istekler cache'ten gelir.
 */
@Service
@ConditionalOnProperty(name = "cache.strategy", havingValue = "lazy", matchIfMissing = true)
@RequiredArgsConstructor
public class LazyCacheStrategy implements CacheStrategy {

    private final CacheProvider cacheProvider;
    private final ProductRepository productRepository;
    private final CacheLogger cacheLogger;

    @Override
    public Product getProduct(Long id) {
        long start = System.nanoTime();
        Optional<Product> cached = cacheProvider.get(id);

        if (cached.isPresent()) {
            long elapsed = System.nanoTime() - start;
            cacheLogger.log(id, "HIT", elapsed, cacheProvider.providerName(), strategyName());
            return cached.get();
        }

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        cacheProvider.put(id, product);
        long elapsed = System.nanoTime() - start;
        cacheLogger.log(id, "MISS", elapsed, cacheProvider.providerName(), strategyName());
        return product;
    }

    @Override
    public void warmUp(List<Product> products) {
        // Lazy strateji önceden ısınmaz
    }

    @Override
    public WarmupStatus getWarmupStatus() {
        // Lazy strateji warm-up gerektirmez — her zaman hazır
        return WarmupStatus.builder()
                .ready(true)
                .loaded(0)
                .total(0)
                .strategy(strategyName())
                .provider(cacheProvider.providerName())
                .build();
    }

    @Override
    public String strategyName() {
        return "lazy";
    }
}

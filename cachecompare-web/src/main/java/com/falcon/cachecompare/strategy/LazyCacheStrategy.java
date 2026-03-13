package com.falcon.cachecompare.strategy;

import com.falcon.cachecompare.cache.CacheProvider;
import com.falcon.cachecompare.entity.Product;
import com.falcon.cachecompare.exception.ProductNotFoundException;
import com.falcon.cachecompare.logging.CacheLogger;
import com.falcon.cachecompare.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

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
        // Lazy strategy does not pre-warm the cache
    }

    @Override
    public String strategyName() {
        return "lazy";
    }
}

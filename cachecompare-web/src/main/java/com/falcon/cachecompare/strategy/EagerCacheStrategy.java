package com.falcon.cachecompare.strategy;

import com.falcon.cachecompare.cache.CacheProvider;
import com.falcon.cachecompare.entity.Product;
import com.falcon.cachecompare.exception.ProductNotFoundException;
import com.falcon.cachecompare.logging.CacheLogger;
import com.falcon.cachecompare.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "cache.strategy", havingValue = "eager")
@RequiredArgsConstructor
public class EagerCacheStrategy implements CacheStrategy, ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(EagerCacheStrategy.class);

    private final CacheProvider cacheProvider;
    private final ProductRepository productRepository;
    private final CacheLogger cacheLogger;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("[EAGER] Warming up cache with all products from database...");
        List<Product> all = productRepository.findAll();
        warmUp(all);
        log.info("[EAGER] Cache warm-up complete. {} products loaded into '{}'.",
                all.size(), cacheProvider.providerName());
    }

    @Override
    public void warmUp(List<Product> products) {
        long start = System.nanoTime();
        Map<Long, Product> map = products.stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        cacheProvider.putAll(map);
        long elapsed = System.nanoTime() - start;
        for (Product p : products) {
            cacheLogger.log(p.getId(), "WARM_UP", elapsed / products.size(),
                    cacheProvider.providerName(), strategyName());
        }
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
    public String strategyName() {
        return "eager";
    }
}

package com.falcon.redisomcompare.service;

import com.falcon.redisomcompare.entity.Product;
import com.falcon.redisomcompare.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DataSeederService {

    private static final Logger log = LoggerFactory.getLogger(DataSeederService.class);

    private static final String[] CATEGORIES =
            {"Electronics", "Clothing", "Books", "Sports", "Home", "Toys", "Food", "Auto"};

    private static final int BATCH_SIZE = 1000;

    private final ProductRepository productRepository;

    /**
     * Belirtilen hedef sayıya ulaşana kadar ürün ekler.
     * Mevcut kayıt sayısı >= targetCount ise işlem yapmaz.
     *
     * @param targetCount hedef kayıt sayısı (örn: 1000, 200000)
     * @return eklenen kayıt sayısı
     */
    public int seed(int targetCount) {
        long current = productRepository.count();
        if (current >= targetCount) {
            log.info("[SEED] Zaten {} kayıt var (hedef {}), atlanıyor.", current, targetCount);
            return 0;
        }

        int start = (int) current + 1;
        int end = targetCount;
        int total = end - start + 1;

        log.info("[SEED] {} kayıt eklenecek ({} → {})...", total, start, end);

        int inserted = 0;
        for (int batchStart = start; batchStart <= end; batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE - 1, end);
            List<Product> batch = new ArrayList<>(batchEnd - batchStart + 1);

            for (int i = batchStart; i <= batchEnd; i++) {
                batch.add(Product.builder()
                        .name("Product " + i)
                        .description("Description for product number " + i + ". A quality item in its category.")
                        .price(BigDecimal.valueOf(9.99 + i))
                        .stock(100 + (i % 50))
                        .category(CATEGORIES[i % CATEGORIES.length])
                        .brand("Brand" + (i % 20 + 1))
                        .sku("SKU-" + String.format("%06d", i))
                        .weight(0.1 + (i % 10) * 0.5)
                        .imageUrl("https://cdn.example.com/products/" + i + ".jpg")
                        .build());
            }

            productRepository.saveAll(batch);
            inserted += batch.size();

            if (inserted % 10_000 == 0 || batchEnd == end) {
                log.info("[SEED] {}/{} kayıt eklendi...", inserted, total);
            }
        }

        log.info("[SEED] Tamamlandı. {} kayıt eklendi.", inserted);
        return inserted;
    }
}

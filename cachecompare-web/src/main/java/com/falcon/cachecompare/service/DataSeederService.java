package com.falcon.cachecompare.service;

import com.falcon.cachecompare.entity.Product;
import com.falcon.cachecompare.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class DataSeederService {

    private static final Logger log = LoggerFactory.getLogger(DataSeederService.class);

    private static final String[] CATEGORIES =
            {"Electronics", "Clothing", "Books", "Sports", "Home", "Toys", "Food", "Auto"};

    private final ProductRepository productRepository;

    public int seed() {
        if (productRepository.count() >= 1000) {
            log.info("[SEED] Already seeded, skipping.");
            return 0;
        }

        List<Product> products = IntStream.rangeClosed(1, 1000)
                .mapToObj(i -> Product.builder()
                        .name("Product " + i)
                        .description("Description for product number " + i + ". A quality item in its category.")
                        .price(BigDecimal.valueOf(9.99 + i))
                        .stock(100 + (i % 50))
                        .category(CATEGORIES[i % CATEGORIES.length])
                        .brand("Brand" + (i % 20 + 1))
                        .sku("SKU-" + String.format("%05d", i))
                        .weight(0.1 + (i % 10) * 0.5)
                        .imageUrl("https://cdn.example.com/products/" + i + ".jpg")
                        .build())
                .toList();

        productRepository.saveAll(products);
        log.info("[SEED] Inserted {} products.", products.size());
        return products.size();
    }
}

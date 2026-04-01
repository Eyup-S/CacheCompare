package com.falcon.redisomcompare.search;

import com.falcon.redisomcompare.entity.Product;
import com.falcon.redisomcompare.entity.ProductDocument;
import com.falcon.redisomcompare.repository.om.ProductDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Redis OM (RediSearch) arama stratejisi.
 *
 * 2-field: FT.SEARCH @category:{cat} @brand:{brand}
 *   → tek komut, TAG index — O(log N)
 *
 * 4-field: FT.SEARCH @category:{cat} @brand:{brand} @stock:[min max]
 *   → stock NUMERIC indexed, price Java'da filtrelenir
 *   → RedisHash'e göre avantaj: stock sunucu tarafında elenir,
 *     fazladan veri transfer azalır
 */
@Service
@Profile("redis-om")
@RequiredArgsConstructor
public class RedisOmSearchStrategy implements SearchStrategy {

    private static final int MAX_PAGE_SIZE = 5000;
    private static final Pageable FIRST_PAGE = PageRequest.of(0, MAX_PAGE_SIZE);

    private final ProductDocumentRepository productDocumentRepository;

    @Override
    public List<Product> searchTwoFields(String category, String brand) {
        return productDocumentRepository
                .findByCategoryAndBrand(category, brand, FIRST_PAGE)
                .stream()
                .map(ProductDocument::toProduct)
                .toList();
    }

    @Override
    public List<Product> searchFourFields(String category, String brand,
                                          BigDecimal priceMin, BigDecimal priceMax,
                                          int stockMin, int stockMax) {
        // stock → RediSearch NUMERIC index; price → Java filtresi
        return productDocumentRepository
                .findByCategoryAndBrandAndStockBetween(
                        category, brand, stockMin, stockMax, FIRST_PAGE)
                .stream()
                .map(ProductDocument::toProduct)
                .filter(p -> p.getPrice() != null
                        && p.getPrice().compareTo(priceMin) >= 0
                        && p.getPrice().compareTo(priceMax) <= 0)
                .toList();
    }

    @Override
    public void loadData(List<Product> products) {
        List<ProductDocument> docs = products.stream()
                .map(p -> ProductDocument.from(p.getId(), p))
                .toList();
        productDocumentRepository.saveAll(docs);
    }

    @Override
    public String providerName() {
        return "redis-om";
    }
}

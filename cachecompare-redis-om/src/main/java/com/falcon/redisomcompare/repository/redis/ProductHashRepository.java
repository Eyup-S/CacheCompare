package com.falcon.redisomcompare.repository.redis;

import com.falcon.redisomcompare.entity.ProductHash;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data Redis @RedisHash repository.
 * findByCategoryAndBrand → SINTER ph:category:{cat} ph:brand:{brand}
 * → Her eşleşen ID için HGETALL ph:{id}
 *
 * Sadece "redis-template" profili aktifken (RedisHashConfig) yüklenir.
 */
@Repository
public interface ProductHashRepository extends CrudRepository<ProductHash, Long> {

    List<ProductHash> findByCategoryAndBrand(String category, String brand);
}

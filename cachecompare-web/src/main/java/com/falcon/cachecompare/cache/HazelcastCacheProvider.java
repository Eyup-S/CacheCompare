package com.falcon.cachecompare.cache;

import com.falcon.cachecompare.entity.Product;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
@Profile("hazelcast")
public class HazelcastCacheProvider implements CacheProvider {

    private final IMap<Long, Product> map;

    public HazelcastCacheProvider(HazelcastInstance hazelcastInstance) {
        this.map = hazelcastInstance.getMap("products");
    }

    @Override
    public Optional<Product> get(Long id) {
        return Optional.ofNullable(map.get(id));
    }

    @Override
    public void put(Long id, Product product) {
        map.put(id, product);
    }

    @Override
    public void putAll(Map<Long, Product> products) {
        map.putAll(products);
    }

    @Override
    public void evict(Long id) {
        map.remove(id);
    }

    @Override
    public String providerName() {
        return "hazelcast";
    }
}

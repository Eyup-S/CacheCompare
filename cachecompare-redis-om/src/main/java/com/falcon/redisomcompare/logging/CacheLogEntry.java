package com.falcon.redisomcompare.logging;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CacheLogEntry {

    private String timestamp;
    private String cacheProvider;
    private String strategy;
    private String datasetSize;   // "1k" veya "200k"
    private Long productId;
    private String result;        // HIT | MISS | WARM_UP
    private Long durationNs;
    private Double durationMs;
    private String threadName;
}

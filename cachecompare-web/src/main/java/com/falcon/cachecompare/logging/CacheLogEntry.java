package com.falcon.cachecompare.logging;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CacheLogEntry {

    private String timestamp;
    private String cacheProvider;
    private String strategy;
    private Long productId;
    private String result;
    private Long durationNs;
    private Double durationMs;
    private String threadName;
}

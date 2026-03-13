package com.falcon.cachecompare.service;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BulkBenchmarkResult {

    private int count;
    private long hits;
    private long misses;
    private long totalWallMs;
    private long avgNs;
    private long minNs;
    private long maxNs;
    private double avgMs;
    private double minMs;
    private double maxMs;
}

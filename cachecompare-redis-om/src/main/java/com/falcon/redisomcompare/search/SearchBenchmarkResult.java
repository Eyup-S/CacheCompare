package com.falcon.redisomcompare.search;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchBenchmarkResult {
    private String provider;
    private String queryType;      // "2field" | "4field"
    private int datasetSize;
    private int queryCount;
    private long totalResultCount;
    private double avgResultCount; // ortalama eşleşme sayısı
    private double avgMs;
    private double minMs;
    private double maxMs;
    private double p50Ms;
    private double p90Ms;
    private double p99Ms;
    private long totalWallMs;
}

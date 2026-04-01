package com.falcon.redisomcompare.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

@Component
public class CacheLogger {

    private static final Logger log = LoggerFactory.getLogger(CacheLogger.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path logFile;
    private final String datasetSizeLabel;

    public CacheLogger(
            @Value("${cache.log.dir:logs-redis-om}") String logDir,
            @Value("${dataset.size:1000}") int datasetSize) throws IOException {
        Path dir = Path.of(logDir);
        Files.createDirectories(dir);
        this.logFile = dir.resolve("cache-benchmark.jsonl");
        this.datasetSizeLabel = datasetSize >= 200_000 ? "200k" : "1k";
    }

    public void log(Long productId, String result, long durationNs,
                    String provider, String strategy) {
        CacheLogEntry entry = CacheLogEntry.builder()
                .timestamp(Instant.now().toString())
                .cacheProvider(provider)
                .strategy(strategy)
                .datasetSize(datasetSizeLabel)
                .productId(productId)
                .result(result)
                .durationNs(durationNs)
                .durationMs(durationNs / 1_000_000.0)
                .threadName(Thread.currentThread().getName())
                .build();
        try {
            String line = mapper.writeValueAsString(entry) + System.lineSeparator();
            Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to write cache log entry", e);
        }
    }
}

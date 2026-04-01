package com.falcon.redisomcompare.strategy;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WarmupStatus {

    private boolean ready;
    private int loaded;
    private int total;
    private String strategy;
    private String provider;

    /** Tamamlanma yüzdesi, 0-100 arası. */
    public int getProgressPct() {
        return total == 0 ? 100 : (int) (loaded * 100L / total);
    }
}

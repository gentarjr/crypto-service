package com.bot.testnet.crypto.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class HealthStatus {

    private boolean healthy;
    private Instant checkedAt;
    private List<String> issues;
    private long checkDurationMs;

    public static HealthStatus healthy(long durationMs) {
        return HealthStatus.builder()
                .healthy(true)
                .checkedAt(Instant.now())
                .issues(new ArrayList<>())
                .checkDurationMs(durationMs)
                .build();
    }

    public static HealthStatus unhealthy(List<String> issues, long durationMs) {
        return HealthStatus.builder()
                .healthy(false)
                .checkedAt(Instant.now())
                .issues(issues)
                .checkDurationMs(durationMs)
                .build();
    }
}

package com.bot.testnet.crypto.model.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class HealthStatusResponse {

    private boolean healthy;
    private Instant checkedAt;
    private List<String> issues;
    private long checkDurationMs;

    public static HealthStatusResponse healthy(long durationMs) {
        return HealthStatusResponse.builder()
                .healthy(true)
                .checkedAt(Instant.now())
                .issues(new ArrayList<>())
                .checkDurationMs(durationMs)
                .build();
    }

    public static HealthStatusResponse unhealthy(List<String> issues, long durationMs) {
        return HealthStatusResponse.builder()
                .healthy(false)
                .checkedAt(Instant.now())
                .issues(issues)
                .checkDurationMs(durationMs)
                .build();
    }
}

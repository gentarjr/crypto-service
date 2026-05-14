package com.bot.testnet.crypto.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Candle {
    private Instant openTime;
    private Instant closeTime;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal volume;
    private String interval;  // "15m", "1h", dll

    /**
     * Helper: candle bullish (close > open)?
     */
    public boolean isBullish() {
        return close.compareTo(open) > 0;
    }

    /**
     * Helper: candle bearish (close < open)?
     */
    public boolean isBearish() {
        return close.compareTo(open) < 0;
    }

    /**
     * Helper: range candle (high - low)
     */
    public BigDecimal getRange() {
        return high.subtract(low);
    }

    /**
     * Helper: body size (abs of close - open)
     */
    public BigDecimal getBodySize() {
        return close.subtract(open).abs();
    }
}

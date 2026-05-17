package com.bot.testnet.crypto.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class Signal {
    // Core fields
    private SignalAction action;         // BUY, SELL, HOLD
    private StrategyType strategy;       // strategi yang generate signal
    private BigDecimal price;            // harga saat signal generated
    private Instant timestamp;           // kapan signal generated

    // Trade plan (diisi kalau action = BUY/SELL)
    private BigDecimal stopLoss;         // calculated SL price
    private BigDecimal takeProfit;       // calculated TP price (tier 1)
    private BigDecimal positionSize;     // berapa USDT yang dipakai
    private BigDecimal riskAmount;       // berapa dollar yang di-risk

    // Context
    private List<SignalFilter> filters;  // semua filter yang dievaluasi
    private String summary;              // human-readable summary

    // ───── Helper methods ─────

    public Signal build() {
        // Validasi hanya untuk BUY signal yang punya SL & TP
        if (action == SignalAction.BUY
                && price != null
                && stopLoss != null
                && takeProfit != null) {

            // SL harus di BAWAH entry
            if (stopLoss.compareTo(price) >= 0) {
                throw new IllegalStateException(String.format(
                        "Invalid BUY signal: SL (%.4f) must be BELOW entry (%.4f)",
                        stopLoss.doubleValue(), price.doubleValue()));
            }

            // TP harus di ATAS entry
            if (takeProfit.compareTo(price) <= 0) {
                throw new IllegalStateException(String.format(
                        "Invalid BUY signal: TP (%.4f) must be ABOVE entry (%.4f)",
                        takeProfit.doubleValue(), price.doubleValue()));
            }
        }

        Signal signal = new Signal();
        signal.action = this.action;
        signal.strategy = this.strategy;
        signal.price = this.price;
        signal.timestamp = this.timestamp;
        signal.stopLoss = this.stopLoss;
        signal.takeProfit = this.takeProfit;
        signal.positionSize = this.positionSize;
        signal.riskAmount = this.riskAmount;
        signal.filters = this.filters;
        signal.summary = this.summary;
        return signal;
    }

/**
     * Apakah signal ini actionable (BUY atau SELL)?
     */
    public boolean isActionable() {
        return action == SignalAction.BUY || action == SignalAction.SELL;
    }

    /**
     * Hitung R:R (Risk/Reward ratio) dari SL dan TP
     */
    public BigDecimal getRiskRewardRatio() {
        if (price == null || stopLoss == null || takeProfit == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal risk = price.subtract(stopLoss).abs();
        BigDecimal reward = takeProfit.subtract(price).abs();
        if (risk.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return reward.divide(risk, 2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Hitung berapa filter yang pass
     */
    public long getPassedFilterCount() {
        if (filters == null) return 0;
        return filters.stream().filter(SignalFilter::isPass).count();
    }

    /**
     * Hitung berapa filter yang fail
     */
    public long getFailedFilterCount() {
        if (filters == null) return 0;
        return filters.stream().filter(f -> !f.isPass()).count();
    }

    // ───── Factory methods untuk common signals ─────

    /**
     * HOLD signal karena transition zone (ADX 20-25)
     */
    public static Signal noTrade(String reason) {
        return Signal.builder()
                .action(SignalAction.HOLD)
                .strategy(StrategyType.NO_TRADE)
                .timestamp(ZonedDateTime.now(ZoneId.of("Asia/Jakarta")).toInstant())
                .summary(reason)
                .build();
    }

    /**
     * HOLD signal karena filter tidak terpenuhi
     */
    public static Signal hold(StrategyType strategy, String reason, List<SignalFilter> filters) {
        return Signal.builder()
                .action(SignalAction.HOLD)
                .strategy(strategy)
                .timestamp(ZonedDateTime.now(ZoneId.of("Asia/Jakarta")).toInstant())
                .summary(reason)
                .filters(filters)
                .build();
    }
}

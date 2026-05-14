package com.bot.testnet.crypto.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Rekaman trade yang sudah closed
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeRecord {

    private String id;
    private StrategyType strategy;
    private BigDecimal entryPrice;
    private BigDecimal exitPrice;
    private BigDecimal positionSize;
    private BigDecimal pnl;             // profit/loss dalam USDT
    private BigDecimal pnlPercent;      // profit/loss dalam %
    private String closeReason;         // "TAKE_PROFIT", "STOP_LOSS", "FORCED_EXIT"
    private Instant openTime;
    private Instant closeTime;
    private long durationMinutes;       // berapa lama posisi terbuka

    /**
     * Apakah trade ini profitable?
     */
    public boolean isWin() {
        return pnl != null && pnl.compareTo(BigDecimal.ZERO) > 0;
    }
}
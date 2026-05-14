package com.bot.testnet.crypto.model;

import com.bot.testnet.crypto.model.dto.StrategyType;
import com.bot.testnet.crypto.service.risk.TrailablePosition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Real position di Binance (live trading)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LivePosition implements TrailablePosition {

    private String id;                  // internal ID
    private String binanceOrderId;      // order ID dari Binance
    private StrategyType strategy;

    // Entry details
    private BigDecimal entryPrice;      // actual fill price
    private BigDecimal requestedPrice;  // harga saat signal
    private BigDecimal quantity;        // berapa BNB yang dibeli
    private BigDecimal positionValue;   // entry × quantity (USDT)
    private BigDecimal fee;             // trading fee

    // Exit levels
    private BigDecimal stopLoss;
    private BigDecimal initialStopLoss;
    private BigDecimal takeProfit;
    private BigDecimal highestPrice;
    private boolean trailingActive;

    // Status
    private String status;              // OPEN, CLOSED, PENDING
    private Instant openTime;
    private Instant closeTime;

    // Close details
    private BigDecimal closePrice;
    private BigDecimal realizedPnl;
    private String closeReason;

    // ─────────────────────────────────────────────
    // Helper Methods
    // ─────────────────────────────────────────────

    public BigDecimal calculateUnrealizedPnl(BigDecimal currentPrice) {
        if (entryPrice == null || quantity == null) return BigDecimal.ZERO;
        return currentPrice.subtract(entryPrice)
                .multiply(quantity)
                .setScale(4, java.math.RoundingMode.HALF_UP);
    }

    public boolean isHitStopLoss(BigDecimal price) {
        if (stopLoss == null) return false;
        return price.compareTo(stopLoss) <= 0;
    }

    public boolean isHitTakeProfit(BigDecimal price) {
        if (takeProfit == null) return false;
        return price.compareTo(takeProfit) >= 0;
    }

    public void updateHighestPrice(BigDecimal price) {
        if (highestPrice == null || price.compareTo(highestPrice) > 0) {
            highestPrice = price;
        }
    }

    public boolean ratchetStopLoss(BigDecimal newSL) {
        if (newSL == null || stopLoss == null) return false;
        if (newSL.compareTo(stopLoss) > 0) {
            stopLoss = newSL;
            return true;
        }
        return false;
    }

    public boolean isBreakevenActivationReached(BigDecimal price) {
        if (entryPrice == null || initialStopLoss == null) return false;
        BigDecimal oneR = entryPrice.subtract(initialStopLoss).abs();
        return price.subtract(entryPrice).compareTo(oneR) >= 0;
    }

    public BigDecimal calculateTrailingSL(BigDecimal atr, double multiplier) {
        if (highestPrice == null || atr == null) return stopLoss;
        return highestPrice.subtract(
                atr.multiply(BigDecimal.valueOf(multiplier)));
    }
}
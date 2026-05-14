package com.bot.testnet.crypto.model.dto;

import com.bot.testnet.crypto.service.risk.TrailablePosition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Representasi posisi virtual (paper trading)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VirtualPosition implements TrailablePosition {

    // Entry details
    private String id;                  // unique ID
    private SignalAction direction;     // BUY (untuk sekarang cuma BUY di spot)
    private StrategyType strategy;      // EMA atau BB
    private BigDecimal entryPrice;      // harga masuk
    private BigDecimal positionSize;    // ukuran posisi (USDT)
    private BigDecimal riskAmount;      // dollar yang di-risk
    private Instant openTime;          // kapan posisi dibuka

    // Exit levels
    private BigDecimal stopLoss;        // SL price
    private BigDecimal initialStopLoss;
    private BigDecimal takeProfit;      // TP price (tier 1)

    private BigDecimal highestPrice;        // ✨ Harga tertinggi sejak entry
    private boolean trailingActive;         // ✨ Apakah trailing sudah aktif?

    // Track unrealized P&L
    private BigDecimal currentPrice;    // harga terakhir
    private BigDecimal unrealizedPnl;   // P&L yang belum direalisasi

    /**
     * Hitung unrealized P&L berdasarkan current price
     */
    public BigDecimal calculateUnrealizedPnl(BigDecimal price) {
        if (entryPrice == null || positionSize == null) return BigDecimal.ZERO;
        BigDecimal priceChange = price.subtract(entryPrice);
        BigDecimal pricePct = priceChange.divide(entryPrice, 6, java.math.RoundingMode.HALF_UP);
        return positionSize.multiply(pricePct).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Apakah hit take profit?
     */
    public boolean isHitTakeProfit(BigDecimal price) {
        if (takeProfit == null) return false;
        return price.compareTo(takeProfit) >= 0;
    }

    /**
     * Apakah hit stop loss?
     */
    public boolean isHitStopLoss(BigDecimal price) {
        if (stopLoss == null) return false;
        return price.compareTo(stopLoss) <= 0;
    }

    /**
     * ✨ Apakah profit sudah >= 1× SL distance? (breakeven activation point)
     *
     * 1R = jarak dari entry ke initial SL
     * Contoh: entry $656, SL $652 → 1R = $4
     * Aktif saat harga = $660 (naik $4 dari entry)
     */
    public boolean isBreakevenActivationReached(BigDecimal price) {
        if (entryPrice == null || initialStopLoss == null) return false;
        BigDecimal oneR = entryPrice.subtract(initialStopLoss).abs();
        BigDecimal profitNow = price.subtract(entryPrice);
        return profitNow.compareTo(oneR) >= 0;
    }

    /**
     * ✨ Update highest price (ratchet up only)
     */
    public void updateHighestPrice(BigDecimal price) {
        if (highestPrice == null || price.compareTo(highestPrice) > 0) {
            highestPrice = price;
        }
    }

    /**
     * ✨ Hitung trailing SL berdasarkan ATR
     * trailingSL = highestPrice - (atr × multiplier)
     */
    public BigDecimal calculateTrailingSL(BigDecimal atr, double multiplier) {
        if (highestPrice == null || atr == null) return stopLoss;
        return highestPrice.subtract(
                atr.multiply(BigDecimal.valueOf(multiplier)));
    }

    /**
     * ✨ Update SL — hanya naik (ratchet mechanism)
     * Return true kalau SL berhasil di-update
     */
    public boolean ratchetStopLoss(BigDecimal newSL) {
        if (newSL == null || stopLoss == null) return false;
        if (newSL.compareTo(stopLoss) > 0) {
            stopLoss = newSL;
            return true;
        }
        return false;
    }
}
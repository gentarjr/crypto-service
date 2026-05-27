package com.bot.testnet.crypto.model.response;

import com.bot.testnet.crypto.model.dto.Candle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GetIndicatorResponse {

    private Instant calculatedAt;
    private Instant candleTime;       // waktu candle yang dipakai
    private BigDecimal currentPrice;  // close price saat ini

    // EMA values
    private BigDecimal emaFast;       // EMA(9)
    private BigDecimal emaSlow;       // EMA(21)

    // Crossover signals (akan dipakai di phase berikutnya)
    private boolean goldenCross;      // EMA9 cross UP EMA21 (terjadi di candle ini)
    private boolean deathCross;       // EMA9 cross DOWN EMA21 (terjadi di candle ini)

    // Trend direction
    private String trend;             // "BULLISH" (EMA9 > EMA21) atau "BEARISH"

    // ✨ NEW: RSI
    private BigDecimal rsi;
    private String rsiZone;  // "OVERSOLD", "NEUTRAL", "OVERBOUGHT", "EXTREME_OVERBOUGHT", "EXTREME_OVERSOLD"

    // ✨ NEW: Volume
    private BigDecimal currentVolume;        // volume candle terakhir
    private BigDecimal volumeMA;             // average volume (20 period)
    private BigDecimal volumeRatio;          // current / MA (e.g., 1.5x)
    private String volumeZone;               // "LOW", "NORMAL", "HIGH_SURGE", "EXTREME"

    private BigDecimal atr;                    // ATR value
    private BigDecimal atrPercent;             // ATR sebagai % dari price
    private String volatilityZone;             // "LOW", "NORMAL", "HIGH", "EXTREME"

    private BigDecimal bbUpper;          // Upper Band
    private BigDecimal bbMiddle;         // Middle Band (SMA20)
    private BigDecimal bbLower;          // Lower Band
    private BigDecimal bbWidth;          // Width %  (volatility indicator)
    private BigDecimal bbPercentB;       // %B (price position in bands)
    private String bbZone;               // "ABOVE_UPPER", "UPPER_HALF", "LOWER_HALF", "BELOW_LOWER", "AT_LOWER", "AT_UPPER"
    private BigDecimal adx;              // ADX value
    private BigDecimal plusDI;           // +DI
    private BigDecimal minusDI;          // -DI
    private String marketRegime;         // "RANGING", "TRANSITION", "TRENDING", "STRONG_TRENDING"
    private String preferredStrategy;    // "BB_MEAN_REVERSION", "NO_TRADE", "EMA_CROSSOVER"

    private List<Candle> recentCandles;

    /**
     * Helper: gap antara EMA fast dan slow
     * Positive = bullish, negative = bearish
     */
    public BigDecimal getEmaGap() {
        if (emaFast == null || emaSlow == null) return BigDecimal.ZERO;
        return emaFast.subtract(emaSlow);
    }

    /**
     * Helper: gap dalam persen relatif terhadap price
     */
    public BigDecimal getEmaGapPercent() {
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return getEmaGap()
                .divide(currentPrice, 6, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * Helper: RSI overbought (>70)?
     */
    public boolean isOverbought() {
        return rsi != null && rsi.compareTo(BigDecimal.valueOf(70)) > 0;
    }

    /**
     * Helper: RSI oversold (<30)?
     */
    public boolean isOversold() {
        return rsi != null && rsi.compareTo(BigDecimal.valueOf(30)) < 0;
    }

    /**
     * Helper: RSI extreme overbought (>80)?
     */
    public boolean isExtremeOverbought() {
        return rsi != null && rsi.compareTo(BigDecimal.valueOf(80)) > 0;
    }

    /**
     * Helper: RSI extreme oversold (<20)?
     */
    public boolean isExtremeOversold() {
        return rsi != null && rsi.compareTo(BigDecimal.valueOf(20)) < 0;
    }

    /**
     * ✨ NEW: Apakah volume current termasuk "surge" (≥ 1.5x average)?
     */
    public boolean isVolumeSurge() {
        if (volumeRatio == null) return false;
        return volumeRatio.compareTo(BigDecimal.valueOf(1.5)) >= 0;
    }

    /**
     * ✨ NEW: Apakah volume current termasuk "low" (< 0.5x average)?
     */
    public boolean isVolumeLow() {
        if (volumeRatio == null) return false;
        return volumeRatio.compareTo(BigDecimal.valueOf(0.5)) < 0;
    }

    /**
     * ✨ NEW: Calculated SL untuk BUY position
     */
    public BigDecimal calculateLongStopLoss(BigDecimal multiplier) {
        if (atr == null || currentPrice == null) return null;
        return currentPrice.subtract(atr.multiply(multiplier));
    }

    /**
     * ✨ NEW: Calculated TP untuk BUY position
     */
    public BigDecimal calculateLongTakeProfit(BigDecimal multiplier) {
        if (atr == null || currentPrice == null) return null;
        return currentPrice.add(atr.multiply(multiplier));
    }

    /**
     * ✨ NEW: SL distance dalam %
     */
    public BigDecimal getSlDistancePercent(BigDecimal multiplier) {
        if (atr == null || currentPrice == null ||
                currentPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal slDistance = atr.multiply(multiplier);
        return slDistance.divide(currentPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * ✨ NEW: Apakah market saat ini high volatility?
     */
    public boolean isHighVolatility() {
        return "HIGH".equals(volatilityZone) || "EXTREME".equals(volatilityZone);
    }

    /**
     * ✨ NEW: Apakah price touch atau break Lower Band?
     */
    public boolean isTouchLowerBand() {
        if (currentPrice == null || bbLower == null) return false;
        return currentPrice.compareTo(bbLower) <= 0;
    }

    /**
     * ✨ NEW: Apakah price touch atau break Upper Band?
     */
    public boolean isTouchUpperBand() {
        if (currentPrice == null || bbUpper == null) return false;
        return currentPrice.compareTo(bbUpper) >= 0;
    }

    /**
     * ✨ NEW: Apakah BB squeeze (bands menyempit)?
     * Default threshold: width < 1.5%
     */
    public boolean isBbSqueeze() {
        if (bbWidth == null) return false;
        return bbWidth.compareTo(BigDecimal.valueOf(1.5)) < 0;
    }

    /**
     * ✨ NEW: Calculated SL untuk BB Strategy
     */
    public BigDecimal calculateBbStopLoss(BigDecimal atrMultiplier) {
        if (bbLower == null || atr == null) return null;
        return bbLower.subtract(atr.multiply(atrMultiplier));
    }

    /**
     * ✨ NEW: Calculated TP untuk BB Strategy (target middle band)
     */
    public BigDecimal getBbTakeProfit() {
        return bbMiddle;
    }
    /**
     * ✨ NEW: Apakah market ranging?
     */
    public boolean isRanging() {
        return "RANGING".equals(marketRegime);
    }

    /**
     * ✨ NEW: Apakah market trending?
     */
    public boolean isTrending() {
        return "TRENDING".equals(marketRegime) || "STRONG_TRENDING".equals(marketRegime);
    }

    /**
     * ✨ NEW: Apakah dalam transition zone (no trade)?
     */
    public boolean isTransitionZone() {
        return "TRANSITION".equals(marketRegime);
    }

    /**
     * ✨ NEW: Apakah trend direction up (+DI > -DI)?
     */
    public boolean isTrendUp() {
        if (plusDI == null || minusDI == null) return false;
        return plusDI.compareTo(minusDI) > 0;
    }

    /**
     * ✨ NEW: Apakah trend direction down (-DI > +DI)?
     */
    public boolean isTrendDown() {
        if (plusDI == null || minusDI == null) return false;
        return minusDI.compareTo(plusDI) > 0;
    }
}

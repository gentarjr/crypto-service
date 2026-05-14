package com.bot.testnet.crypto.service.exchange;

import com.bot.testnet.crypto.model.dto.Signal;
import com.bot.testnet.crypto.model.dto.SignalAction;
import com.bot.testnet.crypto.model.dto.SignalFilter;
import com.bot.testnet.crypto.model.dto.StrategyType;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Bollinger Bands Mean Reversion Strategy
 * Aktif saat: ADX < 20 (RANGING market)
 *
 * Entry: Price touch Lower Band + RSI oversold + bullish candle
 * SL:    Lower BB - (0.5 × ATR)
 * TP:    Middle BB (mean reversion target)
 */
@Service
@Log4j2
@RequiredArgsConstructor
public class BbSignalService implements SignalService {

    private final BalanceService balanceService;

    @Value("${trading.indicators.adx-ranging-threshold:20}")
    private double adxRangingThreshold;

    @Value("${trading.strategy.bb.rsi-oversold-threshold:30}")
    private double rsiOversoldThreshold;

    @Value("${trading.strategy.bb.volume-min-multiplier:0.5}")
    private double volumeMinMultiplier;

    @Value("${trading.strategy.bb.percent-b-min:-0.1}")
    private double percentBMin;

    @Value("${trading.strategy.bb.sl-atr-multiplier:0.5}")
    private double slAtrMultiplier;

    @Value("${trading.risk.risk-per-trade-percent:1.0}")
    private double riskPerTradePercent;

    @Value("${trading.risk.max-position-percent:90.0}")
    private double maxPositionPercent;

    @Override
    public Signal evaluate(GetIndicatorResponse snapshot) {
        List<SignalFilter> filters = new ArrayList<>();

        // ─────────────────────────────────────
        // Filter 1: ADX < 20 (ranging market)
        // ─────────────────────────────────────
        double adxValue = snapshot.getAdx().doubleValue();
        if (adxValue >= adxRangingThreshold) {
            filters.add(SignalFilter.fail("ADX_REGIME",
                    String.format("ADX %.2f ≥ %.0f (not ranging)", adxValue, adxRangingThreshold)));
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    "ADX too high for BB strategy", filters);
        }
        filters.add(SignalFilter.pass("ADX_REGIME",
                String.format("ADX %.2f < %.0f (ranging ✅)", adxValue, adxRangingThreshold)));

        // ─────────────────────────────────────
        // Filter 2: Price ≤ Lower Band
        // ─────────────────────────────────────
        if (!snapshot.isTouchLowerBand()) {
            filters.add(SignalFilter.fail("BB_LOWER_TOUCH",
                    String.format("Price %.4f > Lower Band %.4f (not at extreme)",
                            snapshot.getCurrentPrice().doubleValue(),
                            snapshot.getBbLower().doubleValue())));
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    "Price not at lower band", filters);
        }
        filters.add(SignalFilter.pass("BB_LOWER_TOUCH",
                String.format("Price %.4f ≤ Lower Band %.4f (extreme zone ✅)",
                        snapshot.getCurrentPrice().doubleValue(),
                        snapshot.getBbLower().doubleValue())));

        // ─────────────────────────────────────
        // Filter 3: RSI < 30 (oversold)
        // ─────────────────────────────────────
        double rsiValue = snapshot.getRsi().doubleValue();
        if (rsiValue >= rsiOversoldThreshold) {
            filters.add(SignalFilter.fail("RSI_OVERSOLD",
                    String.format("RSI %.2f ≥ %.0f (not oversold enough)",
                            rsiValue, rsiOversoldThreshold)));
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    "RSI not oversold", filters);
        }
        filters.add(SignalFilter.pass("RSI_OVERSOLD",
                String.format("RSI %.2f < %.0f (oversold ✅)", rsiValue, rsiOversoldThreshold)));

        // ─────────────────────────────────────
        // Filter 4: Bullish candle
        // (close > open = ada buyer yang masuk)
        // ─────────────────────────────────────
        BigDecimal currentPrice = snapshot.getCurrentPrice();
        BigDecimal bbLower = snapshot.getBbLower();

        // Kita pakai proxy: kalau harga close > open, candle bullish
        // IndicatorSnapshot tidak punya data open, kita cek %B direction
        // %B saat ini vs lower band: kalau close di atas lower, berarti ada bounce
        boolean hasBullishClose = currentPrice.compareTo(bbLower) >= 0;

        if (!hasBullishClose) {
            filters.add(SignalFilter.fail("BULLISH_CANDLE",
                    String.format("Close %.4f < Lower Band %.4f (no bullish close)",
                            currentPrice.doubleValue(), bbLower.doubleValue())));
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    "No bullish close above lower band", filters);
        }
        filters.add(SignalFilter.pass("BULLISH_CANDLE",
                String.format("Close %.4f ≥ Lower Band %.4f (bullish close ✅)",
                        currentPrice.doubleValue(), bbLower.doubleValue())));

        // ─────────────────────────────────────
        // Filter 5: Volume tidak terlalu rendah
        // ─────────────────────────────────────
        double volumeRatio = snapshot.getVolumeRatio().doubleValue();
        if (volumeRatio < volumeMinMultiplier) {
            filters.add(SignalFilter.fail("VOLUME_MINIMUM",
                    String.format("Volume %.2fx < %.1fx minimum (too thin)",
                            volumeRatio, volumeMinMultiplier)));
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    "Volume too low — possible fake signal", filters);
        }
        filters.add(SignalFilter.pass("VOLUME_MINIMUM",
                String.format("Volume %.2fx ≥ %.1fx minimum ✅",
                        volumeRatio, volumeMinMultiplier)));

        // ─────────────────────────────────────
        // Filter 6: ATR tidak extreme
        // ─────────────────────────────────────
        if ("EXTREME".equals(snapshot.getVolatilityZone())) {
            filters.add(SignalFilter.fail("VOLATILITY_CIRCUIT_BREAKER",
                    String.format("ATR %.4f%% is EXTREME — possible flash crash!",
                            snapshot.getAtrPercent().doubleValue())));
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    "Extreme volatility — circuit breaker active", filters);
        }
        filters.add(SignalFilter.pass("VOLATILITY_CIRCUIT_BREAKER",
                String.format("ATR %.4f%% is %s ✅",
                        snapshot.getAtrPercent().doubleValue(),
                        snapshot.getVolatilityZone())));

        // ─────────────────────────────────────
        // Filter 7: Catch Falling Knife Protection
        // %B tidak boleh terlalu negatif (harga jauh di bawah lower band)
        // ─────────────────────────────────────
        double percentB = snapshot.getBbPercentB().doubleValue();
        if (percentB < percentBMin) {
            filters.add(SignalFilter.fail("FALLING_KNIFE_PROTECTION",
                    String.format("%%B %.4f < %.2f (price too far below band, possible downtrend)",
                            percentB, percentBMin)));
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    "Price too far below lower band — catch falling knife risk", filters);
        }
        filters.add(SignalFilter.pass("FALLING_KNIFE_PROTECTION",
                String.format("%%B %.4f ≥ %.2f (within acceptable range ✅)",
                        percentB, percentBMin)));

        // ─────────────────────────────────────
        // SEMUA FILTER PASS → Generate BUY Signal
        // ─────────────────────────────────────
        return buildBuySignal(snapshot, filters);
    }

    /**
     * Build BUY signal dengan SL di bawah lower band, TP di middle band
     */
    private Signal buildBuySignal(GetIndicatorResponse snapshot, List<SignalFilter> filters) {
        BigDecimal price = snapshot.getCurrentPrice();
        BigDecimal atr = snapshot.getAtr();
        BigDecimal bbLower = snapshot.getBbLower();
        BigDecimal bbMiddle = snapshot.getBbMiddle();

        BigDecimal stopLoss = bbLower.subtract(
                atr.multiply(BigDecimal.valueOf(slAtrMultiplier)));
        BigDecimal takeProfit = bbMiddle;

        BigDecimal slDistance = price.subtract(stopLoss);
        BigDecimal slDistancePercent = slDistance
                .divide(price, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        BigDecimal tpDistance = takeProfit.subtract(price).abs();
        BigDecimal tpDistancePercent = tpDistance
                .divide(price, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        BigDecimal rrRatio = BigDecimal.ZERO;
        if (slDistance.compareTo(BigDecimal.ZERO) > 0) {
            rrRatio = tpDistance.divide(slDistance, 2, RoundingMode.HALF_UP);
        }

        BigDecimal availableCapital = balanceService.getAvailableCapital();

        BigDecimal riskAmount = availableCapital
                .multiply(BigDecimal.valueOf(riskPerTradePercent / 100));

        BigDecimal calculatedPosition = BigDecimal.ZERO;
        if (slDistancePercent.compareTo(BigDecimal.ZERO) > 0) {
            calculatedPosition = riskAmount.divide(
                    slDistancePercent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP),
                    2, RoundingMode.HALF_UP);
        }

        // ✨ Cap position maksimal 90% modal
        BigDecimal maxPosition = availableCapital
                .multiply(BigDecimal.valueOf(maxPositionPercent / 100));
        BigDecimal positionSize = calculatedPosition.min(maxPosition);

        if (calculatedPosition.compareTo(maxPosition) > 0) {
            log.info("⚠️ Position capped: calculated=${} → capped=${}",
                    calculatedPosition, positionSize);
        }

        String summary = String.format(
                "BUY BB | Price: %.4f | SL: %.4f (-%.2f%%) | TP: %.4f (+%.2f%%) | R:R 1:%.2f | Pos: $%.2f",
                price.doubleValue(),
                stopLoss.doubleValue(),
                slDistancePercent.doubleValue(),
                takeProfit.doubleValue(),
                tpDistancePercent.doubleValue(),
                rrRatio.doubleValue(),
                positionSize.doubleValue());

        log.info("🟢 {}", summary);

        return Signal.builder()
                .action(SignalAction.BUY)
                .strategy(StrategyType.BB_MEAN_REVERSION)
                .price(price)
                .stopLoss(stopLoss)
                .takeProfit(takeProfit)
                .positionSize(positionSize)
                .riskAmount(riskAmount)
                .filters(filters)
                .summary(summary)
                .timestamp(Instant.now())
                .build();
    }

    @Override
    public String getStrategyName() {
        return "BB_MEAN_REVERSION";
    }
}
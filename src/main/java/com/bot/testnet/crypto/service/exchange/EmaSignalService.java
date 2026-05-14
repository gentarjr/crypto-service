package com.bot.testnet.crypto.service.exchange;

import com.bot.testnet.crypto.model.dto.Signal;
import com.bot.testnet.crypto.model.dto.SignalAction;
import com.bot.testnet.crypto.model.dto.SignalFilter;
import com.bot.testnet.crypto.model.dto.StrategyType;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import com.bot.testnet.crypto.service.indicator.MultiTimeframeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Log4j2
public class EmaSignalService implements SignalService{

    @Value("${trading.indicators.adx-trending-threshold:25}")
    private double adxTrendingThreshold;

    @Value("${trading.strategy.ema.volume-surge-multiplier:1.5}")
    private double volumeSurgeMultiplier;

    @Value("${trading.strategy.ema.rsi-max-threshold:70}")
    private double rsiMaxThreshold;

    @Value("${trading.strategy.ema.price-extension-max:1.03}")
    private double priceExtensionMax;

    @Value("${trading.strategy.ema.atr-extreme-multiplier:2.5}")
    private double atrExtremeMultiplier;

    @Value("${trading.risk.sl-atr-multiplier:1.5}")
    private double slAtrMultiplier;

    @Value("${trading.risk.tp-atr-multiplier:2.0}")
    private double tpAtrMultiplier;

    @Value("${trading.risk.risk-per-trade-percent:1.0}")
    private double riskPerTradePercent;

    @Value("${trading.risk.max-position-percent:90.0}")
    private double maxPositionPercent;

    @Value("${trading.mta.enabled:true}")
    private boolean mtaEnabled;

    private final MultiTimeframeService multiTimeframeService;
    private final CandleService candleService;
    private final BalanceService balanceService;

    @Override
    public Signal evaluate(GetIndicatorResponse snapshot) {
        List<SignalFilter> filters = new ArrayList<>();

        // ─────────────────────────────────────
        // Filter 1: ADX > threshold (trending)
        // ─────────────────────────────────────
        double adxValue = snapshot.getAdx().doubleValue();
        if (adxValue <= adxTrendingThreshold) {
            filters.add(SignalFilter.fail("ADX_REGIME",
                    String.format("ADX %.2f ≤ %.0f (not trending)", adxValue, adxTrendingThreshold)));
            return Signal.hold(StrategyType.EMA_CROSSOVER,
                    "ADX below trending threshold", filters);
        }
        filters.add(SignalFilter.pass("ADX_REGIME",
                String.format("ADX %.2f > %.0f (trending ✅)", adxValue, adxTrendingThreshold)));

        // ─────────────────────────────────────
        // Filter 2: +DI > -DI (direction UP)
        // ─────────────────────────────────────
        if (!snapshot.isTrendUp()) {
            filters.add(SignalFilter.fail("TREND_DIRECTION",
                    String.format("+DI %.2f < -DI %.2f (direction DOWN)",
                            snapshot.getPlusDI().doubleValue(),
                            snapshot.getMinusDI().doubleValue())));
            return Signal.hold(StrategyType.EMA_CROSSOVER,
                    "Trend direction is DOWN", filters);
        }
        filters.add(SignalFilter.pass("TREND_DIRECTION",
                String.format("+DI %.2f > -DI %.2f (direction UP ✅)",
                        snapshot.getPlusDI().doubleValue(),
                        snapshot.getMinusDI().doubleValue())));

        // ─────────────────────────────────────
        // Filter 3: EMA9 cross UP EMA21 (signal utama)
        // ─────────────────────────────────────
        if (!snapshot.isGoldenCross()) {
            filters.add(SignalFilter.fail("EMA_CROSSOVER",
                    String.format("No golden cross. EMA9=%.4f, EMA21=%.4f",
                            snapshot.getEmaFast().doubleValue(),
                            snapshot.getEmaSlow().doubleValue())));
            return Signal.hold(StrategyType.EMA_CROSSOVER,
                    "No EMA golden cross detected", filters);
        }
        filters.add(SignalFilter.pass("EMA_CROSSOVER",
                String.format("Golden cross! EMA9=%.4f crossed above EMA21=%.4f ✅",
                        snapshot.getEmaFast().doubleValue(),
                        snapshot.getEmaSlow().doubleValue())));

        // ─────────────────────────────────────
        // Filter 4: Volume >= MA × multiplier
        // ─────────────────────────────────────
        double volumeRatio = snapshot.getVolumeRatio().doubleValue();
        if (volumeRatio < volumeSurgeMultiplier) {
            filters.add(SignalFilter.fail("VOLUME_SURGE",
                    String.format("Volume %.2fx < %.1fx threshold (insufficient volume)",
                            volumeRatio, volumeSurgeMultiplier)));
            return Signal.hold(StrategyType.EMA_CROSSOVER,
                    "Volume insufficient for confirmation", filters);
        }
        filters.add(SignalFilter.pass("VOLUME_SURGE",
                String.format("Volume %.2fx ≥ %.1fx threshold ✅", volumeRatio, volumeSurgeMultiplier)));

        // ─────────────────────────────────────
        // Filter 5: RSI < rsiMaxThreshold (not overbought)
        // ─────────────────────────────────────
        double rsiValue = snapshot.getRsi().doubleValue();
        if (rsiValue > rsiMaxThreshold) {
            filters.add(SignalFilter.fail("RSI_NOT_OVERBOUGHT",
                    String.format("RSI %.2f > %.0f (overbought, late entry risk)",
                            rsiValue, rsiMaxThreshold)));
            return Signal.hold(StrategyType.EMA_CROSSOVER,
                    "RSI overbought — avoid late entry", filters);
        }
        filters.add(SignalFilter.pass("RSI_NOT_OVERBOUGHT",
                String.format("RSI %.2f ≤ %.0f (healthy momentum ✅)", rsiValue, rsiMaxThreshold)));

        // ─────────────────────────────────────
        // Filter 6: Price tidak terlalu extended dari EMA21
        // Anti-FOMO filter: hindari beli setelah harga sudah pump jauh
        // ─────────────────────────────────────
        BigDecimal currentPrice = snapshot.getCurrentPrice();
        BigDecimal emaSlow = snapshot.getEmaSlow();
        BigDecimal maxAllowedPrice = emaSlow.multiply(BigDecimal.valueOf(priceExtensionMax));

        if (currentPrice.compareTo(maxAllowedPrice) > 0) {
            double extensionPercent = currentPrice.subtract(emaSlow)
                    .divide(emaSlow, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();
            filters.add(SignalFilter.fail("PRICE_EXTENSION",
                    String.format("Price %.2f is %.2f%% above EMA21 (max %.0f%%)",
                            currentPrice.doubleValue(),
                            extensionPercent,
                            (priceExtensionMax - 1) * 100)));
            return Signal.hold(StrategyType.EMA_CROSSOVER,
                    "Price too extended from EMA21 (FOMO risk)", filters);
        }
        double extensionPercent = currentPrice.subtract(emaSlow)
                .divide(emaSlow, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
        filters.add(SignalFilter.pass("PRICE_EXTENSION",
                String.format("Price %.2f is %.2f%% above EMA21 (within limit ✅)",
                        currentPrice.doubleValue(), extensionPercent)));

        // ─────────────────────────────────────
        // Filter 7: ATR tidak extreme (volatility circuit breaker)
        // ─────────────────────────────────────
        if ("EXTREME".equals(snapshot.getVolatilityZone())) {
            filters.add(SignalFilter.fail("VOLATILITY_CIRCUIT_BREAKER",
                    String.format("ATR %.4f%% is EXTREME — possible news event",
                            snapshot.getAtrPercent().doubleValue())));
            return Signal.hold(StrategyType.EMA_CROSSOVER,
                    "Extreme volatility — circuit breaker", filters);
        }
        filters.add(SignalFilter.pass("VOLATILITY_CIRCUIT_BREAKER",
                String.format("ATR %.4f%% is %s — normal range ✅",
                        snapshot.getAtrPercent().doubleValue(),
                        snapshot.getVolatilityZone())));

        if (mtaEnabled) {
            String trend1h = multiTimeframeService.get1hTrend(candleService);
            BigDecimal ema50_1h = multiTimeframeService.getEma50_1h(candleService);

            if ("UNKNOWN".equals(trend1h)) {
                // Tidak bisa fetch 1h data → skip filter (don't block)
                filters.add(SignalFilter.pass("MTA_1H",
                        "1h data unavailable — filter skipped"));
            } else if (!"BULLISH".equals(trend1h)) {
                filters.add(SignalFilter.fail("MTA_1H",
                        String.format("1h trend BEARISH (price below EMA50(1h)=%.4f) — counter-trend",
                                ema50_1h != null ? ema50_1h.doubleValue() : 0)));
                return Signal.hold(StrategyType.EMA_CROSSOVER,
                        "Counter-trend: 1h bearish", filters);
            } else {
                filters.add(SignalFilter.pass("MTA_1H",
                        String.format("1h trend BULLISH (price > EMA50(1h)=%.4f) ✅",
                                ema50_1h != null ? ema50_1h.doubleValue() : 0)));
            }
        }
        return buildBuySignal(snapshot, filters);
    }

    /**
     * Build BUY signal dengan SL, TP, position size
     */
    private Signal buildBuySignal(GetIndicatorResponse snapshot, List<SignalFilter> filters) {
        BigDecimal price = snapshot.getCurrentPrice();
        BigDecimal atr = snapshot.getAtr();

        // SL = Entry - (1.5 × ATR)
        BigDecimal stopLoss = price.subtract(
                atr.multiply(BigDecimal.valueOf(slAtrMultiplier)));

        // TP1 = Entry + (2.0 × ATR)
        BigDecimal takeProfit = price.add(
                atr.multiply(BigDecimal.valueOf(tpAtrMultiplier)));

        // SL distance
        BigDecimal slDistance = price.subtract(stopLoss);
        BigDecimal slDistancePercent = slDistance
                .divide(price, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        // Position sizing: Risk Amount / SL Distance
        BigDecimal availableCapital = balanceService.getAvailableCapital();

        BigDecimal riskAmount = availableCapital.multiply(BigDecimal.valueOf(riskPerTradePercent / 100));
        // Calculated position dari risk
        BigDecimal calculatedPosition = riskAmount.divide(
                slDistancePercent.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP),
                2, RoundingMode.HALF_UP);

        // ✨ Cap position maksimal 90% modal
        BigDecimal maxPosition = availableCapital.multiply(BigDecimal.valueOf(maxPositionPercent / 100));
        BigDecimal positionSize = calculatedPosition.min(maxPosition);

        if (calculatedPosition.compareTo(maxPosition) > 0) {
            log.info("⚠️ Position capped: calculated=${} → capped=${}",
                    calculatedPosition, positionSize);
        }

        BigDecimal reward = takeProfit.subtract(price);
        BigDecimal risk = price.subtract(stopLoss);
        BigDecimal rrRatio = reward.divide(risk, 2, RoundingMode.HALF_UP);

        // Summary
        String summary = String.format(
                "BUY EMA | Price: %.4f | SL: %.4f (-%.2f%%) | TP: %.4f | R:R 1:%.2f | Pos: $%.2f",
                price.doubleValue(),
                stopLoss.doubleValue(),
                slDistancePercent.doubleValue(),
                takeProfit.doubleValue(),
                rrRatio.doubleValue(),
                positionSize.doubleValue());

        log.info("🟢 {}", summary);

        return Signal.builder()
                .action(SignalAction.BUY)
                .strategy(StrategyType.EMA_CROSSOVER)
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
        return "EMA_CROSSOVER";
    }

    public List<SignalFilter> evaluateAllFilters(GetIndicatorResponse snapshot) {
        List<SignalFilter> filters = new ArrayList<>();

        // Filter 1: ADX
        double adx = snapshot.getAdx().doubleValue();
        boolean f1 = adx > adxTrendingThreshold;
        filters.add(f1
                ? SignalFilter.pass("ADX_REGIME",
                String.format("ADX %.2f > %.0f (trending ✅)", adx, adxTrendingThreshold))
                : SignalFilter.fail("ADX_REGIME",
                String.format("ADX %.2f ≤ %.0f (not trending)", adx, adxTrendingThreshold)));

        // Filter 2: Trend direction
        boolean f2 = snapshot.isTrendUp();
        filters.add(f2
                ? SignalFilter.pass("TREND_DIRECTION",
                String.format("+DI %.2f > -DI %.2f (UP ✅)",
                        snapshot.getPlusDI().doubleValue(),
                        snapshot.getMinusDI().doubleValue()))
                : SignalFilter.fail("TREND_DIRECTION",
                String.format("+DI %.2f < -DI %.2f (DOWN)",
                        snapshot.getPlusDI().doubleValue(),
                        snapshot.getMinusDI().doubleValue())));

        // Filter 3: Golden Cross
        boolean f3 = snapshot.isGoldenCross();
        filters.add(f3
                ? SignalFilter.pass("EMA_CROSSOVER",
                String.format("Golden cross! EMA9=%.2f > EMA21=%.2f ✅",
                        snapshot.getEmaFast().doubleValue(),
                        snapshot.getEmaSlow().doubleValue()))
                : SignalFilter.fail("EMA_CROSSOVER",
                String.format("No cross. EMA9=%.2f, EMA21=%.2f (gap: %.2f)",
                        snapshot.getEmaFast().doubleValue(),
                        snapshot.getEmaSlow().doubleValue(),
                        snapshot.getEmaSlow().subtract(snapshot.getEmaFast()).doubleValue())));

        // Filter 4: Volume
        double volRatio = snapshot.getVolumeRatio().doubleValue();
        boolean f4 = volRatio >= volumeSurgeMultiplier;
        filters.add(f4
                ? SignalFilter.pass("VOLUME_SURGE",
                String.format("Volume %.2fx ≥ %.1fx ✅", volRatio, volumeSurgeMultiplier))
                : SignalFilter.fail("VOLUME_SURGE",
                String.format("Volume %.2fx < %.1fx (need %.2fx more)",
                        volRatio, volumeSurgeMultiplier,
                        volumeSurgeMultiplier - volRatio)));

        // Filter 5: RSI
        double rsi = snapshot.getRsi().doubleValue();
        boolean f5 = rsi <= rsiMaxThreshold;
        filters.add(f5
                ? SignalFilter.pass("RSI_NOT_OVERBOUGHT",
                String.format("RSI %.2f ≤ %.0f ✅", rsi, rsiMaxThreshold))
                : SignalFilter.fail("RSI_NOT_OVERBOUGHT",
                String.format("RSI %.2f > %.0f (overbought)", rsi, rsiMaxThreshold)));

        // Filter 6: Price extension
        BigDecimal price = snapshot.getCurrentPrice();
        BigDecimal emaSlow = snapshot.getEmaSlow();
        BigDecimal maxPrice = emaSlow.multiply(BigDecimal.valueOf(priceExtensionMax));
        boolean f6 = price.compareTo(maxPrice) <= 0;
        double extPct = price.subtract(emaSlow)
                .divide(emaSlow, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
        filters.add(f6
                ? SignalFilter.pass("PRICE_EXTENSION",
                String.format("Price %.2f%% above EMA21 (within 3%% limit ✅)", extPct))
                : SignalFilter.fail("PRICE_EXTENSION",
                String.format("Price %.2f%% above EMA21 (max 3%%)", extPct)));

        // Filter 7: ATR
        boolean f7 = !"EXTREME".equals(snapshot.getVolatilityZone());
        filters.add(f7
                ? SignalFilter.pass("VOLATILITY_CB",
                String.format("ATR %.2f%% is %s ✅",
                        snapshot.getAtrPercent().doubleValue(),
                        snapshot.getVolatilityZone()))
                : SignalFilter.fail("VOLATILITY_CB",
                String.format("ATR %.2f%% is EXTREME — circuit breaker!",
                        snapshot.getAtrPercent().doubleValue())));

        // Filter 8: MTA
        if (mtaEnabled) {
            String trend1h = multiTimeframeService.get1hTrend(candleService);
            BigDecimal ema50 = multiTimeframeService.getEma50_1h(candleService);
            boolean f8 = "BULLISH".equals(trend1h) || "UNKNOWN".equals(trend1h);
            filters.add(f8
                    ? SignalFilter.pass("MTA_1H",
                    String.format("1h trend %s (price > EMA50 $%.2f ✅)",
                            trend1h,
                            ema50 != null ? ema50.doubleValue() : 0))
                    : SignalFilter.fail("MTA_1H",
                    String.format("1h trend BEARISH (price below EMA50 $%.2f)",
                            ema50 != null ? ema50.doubleValue() : 0)));
        }

        return filters;
    }
}

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
public class EmaSignalService implements SignalService {

    private final MultiTimeframeService multiTimeframeService;
    private final CandleService candleService;

    // ─── Config ───────────────────────────────────────
    @Value("${trading.indicators.adx-trending-threshold:25}")
    private double adxTrendingThreshold;

    @Value("${trading.strategy.ema.buy-score-threshold:60}")
    private int buyScoreThreshold;

    @Value("${trading.strategy.ema.strong-buy-score-threshold:80}")
    private int strongBuyScoreThreshold;

    @Value("${trading.strategy.ema.rsi-max-threshold:70}")
    private double rsiMaxThreshold;

    @Value("${trading.strategy.ema.price-extension-max:1.03}")
    private double priceExtensionMax;

    @Value("${trading.risk.sl-atr-multiplier:1.5}")
    private double slAtrMultiplier;

    @Value("${trading.risk.tp-atr-multiplier:2.0}")
    private double tpAtrMultiplier;

    @Value("${trading.risk.modal:300}")
    private double modal;

    @Value("${trading.risk.risk-per-trade-percent:1.0}")
    private double riskPerTradePercent;

    @Value("${trading.risk.max-position-percent:90.0}")
    private double maxPositionPercent;

    @Value("${trading.mta.enabled:true}")
    private boolean mtaEnabled;

    // ─── Evaluate ─────────────────────────────────────
    @Override
    public Signal evaluate(GetIndicatorResponse snapshot) {
        List<SignalFilter> filters = new ArrayList<>();
        int score = 0;

        // ═══════════════════════════════════════
        // MANDATORY — kalau fail langsung HOLD
        // ═══════════════════════════════════════

        // M1: ADX > 25
        double adx = snapshot.getAdx().doubleValue();
        if (adx <= adxTrendingThreshold) {
            filters.add(SignalFilter.fail("ADX_REGIME",
                    String.format("ADX %.2f ≤ %.0f (not trending)", adx, adxTrendingThreshold)));
            return Signal.hold(StrategyType.EMA_CROSSOVER, "Market not trending", filters);
        }
        filters.add(SignalFilter.pass("ADX_REGIME",
                String.format("ADX %.2f > %.0f ✅", adx, adxTrendingThreshold)));

        // M2: +DI > -DI
        if (!snapshot.isTrendUp()) {
            filters.add(SignalFilter.fail("TREND_DIRECTION",
                    String.format("+DI %.2f < -DI %.2f (direction DOWN)",
                            snapshot.getPlusDI().doubleValue(),
                            snapshot.getMinusDI().doubleValue())));
            return Signal.hold(StrategyType.EMA_CROSSOVER, "Trend direction is DOWN", filters);
        }
        filters.add(SignalFilter.pass("TREND_DIRECTION",
                String.format("+DI %.2f > -DI %.2f (UP ✅)",
                        snapshot.getPlusDI().doubleValue(),
                        snapshot.getMinusDI().doubleValue())));

        // M3: EMA9 > EMA21 (uptrend — tidak harus cross!)
        boolean emaUptrend = snapshot.getEmaFast()
                .compareTo(snapshot.getEmaSlow()) > 0;
        if (!emaUptrend) {
            filters.add(SignalFilter.fail("EMA_UPTREND",
                    String.format("EMA9 %.4f < EMA21 %.4f (bearish)",
                            snapshot.getEmaFast().doubleValue(),
                            snapshot.getEmaSlow().doubleValue())));
            return Signal.hold(StrategyType.EMA_CROSSOVER, "EMA bearish", filters);
        }
        filters.add(SignalFilter.pass("EMA_UPTREND",
                String.format("EMA9 %.4f > EMA21 %.4f ✅",
                        snapshot.getEmaFast().doubleValue(),
                        snapshot.getEmaSlow().doubleValue())));

        // M4: ATR extreme = hard block
        if ("EXTREME".equals(snapshot.getVolatilityZone())) {
            filters.add(SignalFilter.fail("ATR_EXTREME",
                    String.format("ATR %.4f%% EXTREME — circuit breaker!",
                            snapshot.getAtrPercent().doubleValue())));
            return Signal.hold(StrategyType.EMA_CROSSOVER, "Extreme volatility", filters);
        }

        // ═══════════════════════════════════════
        // SCORING — menambah confidence
        // ═══════════════════════════════════════

        // S1: Golden Cross (+30) atau trend continuation (+10)
        if (snapshot.isGoldenCross()) {
            score += 30;
            filters.add(SignalFilter.pass("GOLDEN_CROSS",
                    String.format("+30pts | Golden cross! EMA9=%.4f crossed EMA21=%.4f ✅",
                            snapshot.getEmaFast().doubleValue(),
                            snapshot.getEmaSlow().doubleValue())));
        } else {
            score += 10;
            filters.add(SignalFilter.pass("EMA_CONTINUATION",
                    String.format("+10pts | EMA uptrend continuing (EMA9=%.4f > EMA21=%.4f)",
                            snapshot.getEmaFast().doubleValue(),
                            snapshot.getEmaSlow().doubleValue())));
        }

        // S2: Volume scoring
        // S2: Volume scoring — tambah guard minimum
        double volRatio = snapshot.getVolumeRatio().doubleValue();
        if (volRatio >= 1.5) {
            score += 20;
            filters.add(SignalFilter.pass("VOLUME",
                    String.format("+20pts | Volume surge %.2fx ≥ 1.5x ✅", volRatio)));
        } else if (volRatio >= 1.0) {
            score += 10;
            filters.add(SignalFilter.pass("VOLUME",
                    String.format("+10pts | Volume ok %.2fx ≥ 1.0x", volRatio)));
        } else if (volRatio >= 0.7) {
            // ✅ Partial — tidak 0, tidak full
            score += 3;
            filters.add(SignalFilter.pass("VOLUME",
                    String.format("+3pts | Volume below average %.2fx (caution)", volRatio)));
        } else {
            // ✅ Volume sangat rendah = signal tidak reliable
            score -= 5;  // ← Penalty! Kurangi score
            filters.add(SignalFilter.fail("VOLUME",
                    String.format("-5pts | Volume very low %.2fx < 0.7x (unreliable signal)", volRatio)));
        }

        // S3: RSI scoring
        double rsi = snapshot.getRsi().doubleValue();
        if (rsi >= 40 && rsi <= 60) {
            score += 15;
            filters.add(SignalFilter.pass("RSI",
                    String.format("+15pts | RSI %.2f in sweet spot (40-60) ✅", rsi)));
        } else if (rsi < rsiMaxThreshold) {
            score += 10;
            filters.add(SignalFilter.pass("RSI",
                    String.format("+10pts | RSI %.2f ok (< %.0f)", rsi, rsiMaxThreshold)));
        } else {
            filters.add(SignalFilter.fail("RSI",
                    String.format("+0pts | RSI %.2f overbought (≥ %.0f)", rsi, rsiMaxThreshold)));
        }

        // S4: Volatility scoring
        String volZone = snapshot.getVolatilityZone();
        if ("NORMAL".equals(volZone) || "LOW".equals(volZone)) {
            score += 15;
            filters.add(SignalFilter.pass("VOLATILITY",
                    String.format("+15pts | ATR %s ✅", volZone)));
        } else {
            score += 5;
            filters.add(SignalFilter.pass("VOLATILITY",
                    String.format("+5pts | ATR %s (elevated but ok)", volZone)));
        }

        // S5: Price extension scoring
        BigDecimal currentPrice = snapshot.getCurrentPrice();
        BigDecimal emaSlow = snapshot.getEmaSlow();
        BigDecimal maxAllowedPrice = emaSlow.multiply(BigDecimal.valueOf(priceExtensionMax));
        double extensionPct = currentPrice.subtract(emaSlow)
                .divide(emaSlow, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();

        if (currentPrice.compareTo(maxAllowedPrice) <= 0) {
            score += 10;
            filters.add(SignalFilter.pass("PRICE_EXTENSION",
                    String.format("+10pts | Price %.2f%% from EMA21 (within %.0f%% limit ✅)",
                            extensionPct, (priceExtensionMax - 1) * 100)));
        } else {
            filters.add(SignalFilter.fail("PRICE_EXTENSION",
                    String.format("+0pts | Price %.2f%% above EMA21 (max %.0f%%)",
                            extensionPct, (priceExtensionMax - 1) * 100)));
        }

        // S6: MTA 1h scoring
        if (mtaEnabled) {
            String trend1h = multiTimeframeService.get1hTrend(candleService);
            BigDecimal ema50_1h = multiTimeframeService.getEma50_1h(candleService);
            double ema50Val = ema50_1h != null ? ema50_1h.doubleValue() : 0;

            if ("BULLISH".equals(trend1h)) {
                score += 20;
                filters.add(SignalFilter.pass("MTA_1H",
                        String.format("+20pts | 1h BULLISH (above EMA50 $%.4f) ✅", ema50Val)));
            } else if ("UNKNOWN".equals(trend1h)) {
                score += 5;
                filters.add(SignalFilter.pass("MTA_1H",
                        "+5pts | 1h data unavailable (skipped)"));
            } else {
                filters.add(SignalFilter.fail("MTA_1H",
                        String.format("+0pts | 1h BEARISH (below EMA50 $%.4f)", ema50Val)));
            }
        }

        // ═══════════════════════════════════════
        // DECISION
        // ═══════════════════════════════════════
        score = Math.min(score, 100);
        log.info("📊 [EMA] Score: {}/100 | Threshold: {}", score, buyScoreThreshold);

        if (score < buyScoreThreshold) {
            return Signal.hold(StrategyType.EMA_CROSSOVER,
                    String.format("Score %d < %d (need %d more points)",
                            score, buyScoreThreshold, buyScoreThreshold - score),
                    filters);
        }

        // BUY — strong buy = 100% size, normal = 75% size
        double posMultiplier = score >= strongBuyScoreThreshold ? 1.0 : 0.75;
        return buildBuySignal(snapshot, filters, score, posMultiplier);
    }

    // ─── Build Signal ──────────────────────────────────
    private Signal buildBuySignal(GetIndicatorResponse snapshot,
                                  List<SignalFilter> filters,
                                  int score,
                                  double posMultiplier) {
        BigDecimal price = snapshot.getCurrentPrice();
        BigDecimal atr = snapshot.getAtr();

        BigDecimal stopLoss = price.subtract(
                atr.multiply(BigDecimal.valueOf(slAtrMultiplier)));
        BigDecimal takeProfit = price.add(
                atr.multiply(BigDecimal.valueOf(tpAtrMultiplier)));

        BigDecimal slDistance = price.subtract(stopLoss);
        BigDecimal slDistancePct = slDistance
                .divide(price, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        // ✅ Pakai modal dari @Value (sama seperti code Anda)
        BigDecimal riskAmount = BigDecimal.valueOf(modal * riskPerTradePercent / 100);

        BigDecimal calculatedPos = riskAmount.divide(
                slDistancePct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP),
                2, RoundingMode.HALF_UP);

        // Cap + position multiplier (strong buy = full, normal = 75%)
        BigDecimal maxPos = BigDecimal.valueOf(modal * maxPositionPercent / 100)
                .multiply(BigDecimal.valueOf(posMultiplier));
        BigDecimal positionSize = calculatedPos.min(maxPos);

        if (calculatedPos.compareTo(maxPos) > 0) {
            log.info("⚠️ Position capped: ${} → ${}", calculatedPos, positionSize);
        }

        BigDecimal rrRatio = takeProfit.subtract(price)
                .divide(price.subtract(stopLoss), 2, RoundingMode.HALF_UP);

        String signalType = score >= strongBuyScoreThreshold ? "🚀 STRONG BUY" : "✅ BUY";
        String summary = String.format(
                "%s | Score: %d/100 | Price: %.4f | SL: %.4f (-%.2f%%) | TP: %.4f | R:R 1:%.2f | Pos: $%.2f",
                signalType, score,
                price.doubleValue(), stopLoss.doubleValue(),
                slDistancePct.doubleValue(), takeProfit.doubleValue(),
                rrRatio.doubleValue(), positionSize.doubleValue());

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

    // ─── evaluateAllFilters (untuk /signal/why endpoint) ─
    public List<SignalFilter> evaluateAllFilters(GetIndicatorResponse snapshot) {
        Signal s = evaluate(snapshot);
        return s.getFilters() != null ? s.getFilters() : new ArrayList<>();
    }

    @Override
    public String getStrategyName() {
        return "EMA_CROSSOVER";
    }
}
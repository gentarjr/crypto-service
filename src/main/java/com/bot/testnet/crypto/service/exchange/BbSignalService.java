package com.bot.testnet.crypto.service.exchange;

import com.bot.testnet.crypto.model.dto.Signal;
import com.bot.testnet.crypto.model.dto.SignalAction;
import com.bot.testnet.crypto.model.dto.SignalFilter;
import com.bot.testnet.crypto.model.dto.StrategyType;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Log4j2
public class BbSignalService implements SignalService {

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

    @Value("${trading.strategy.bb.buy-score-threshold:55}")
    private int buyScoreThreshold;

    @Value("${trading.strategy.bb.strong-buy-score-threshold:75}")
    private int strongBuyScoreThreshold;

    @Value("${trading.risk.modal:300}")
    private double modal;

    @Value("${trading.risk.risk-per-trade-percent:1.0}")
    private double riskPerTradePercent;

    @Value("${trading.risk.max-position-percent:90.0}")
    private double maxPositionPercent;

    @Override
    public Signal evaluate(GetIndicatorResponse snapshot) {
        List<SignalFilter> filters = new ArrayList<>();
        int score = 0;

        // ═══════════════════════════════════════
        // MANDATORY — kalau fail langsung HOLD
        // ═══════════════════════════════════════

        // M1: ADX < 20 (ranging regime)
        double adx = snapshot.getAdx().doubleValue();
        if (adx >= adxRangingThreshold) {
            filters.add(SignalFilter.fail("ADX_REGIME",
                    String.format("ADX %.2f ≥ %.0f (not ranging)", adx, adxRangingThreshold)));
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    "ADX too high for BB strategy", filters);
        }
        filters.add(SignalFilter.pass("ADX_REGIME",
                String.format("ADX %.2f < %.0f (ranging ✅)", adx, adxRangingThreshold)));

        // M2: ATR extreme = hard block
        if ("EXTREME".equals(snapshot.getVolatilityZone())) {
            filters.add(SignalFilter.fail("ATR_EXTREME",
                    String.format("ATR %.4f%% EXTREME — possible flash crash!",
                            snapshot.getAtrPercent().doubleValue())));
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    "Extreme volatility — circuit breaker", filters);
        }

        // M3: Falling knife protection (hard block)
        double percentB = snapshot.getBbPercentB().doubleValue();
        if (percentB < percentBMin) {
            filters.add(SignalFilter.fail("FALLING_KNIFE",
                    String.format("%%B %.4f < %.2f (price falling too fast — catch falling knife risk)",
                            percentB, percentBMin)));
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    "Catch falling knife risk", filters);
        }
        filters.add(SignalFilter.pass("FALLING_KNIFE",
                String.format("%%B %.4f ≥ %.2f ✅", percentB, percentBMin)));

        // ═══════════════════════════════════════
        // SCORING — menambah confidence
        // ═══════════════════════════════════════

        BigDecimal currentPrice = snapshot.getCurrentPrice();
        BigDecimal bbLower = snapshot.getBbLower();
        BigDecimal bbMiddle = snapshot.getBbMiddle();

        // S1: BB position scoring (paling penting di BB strategy)
        // Harga di lower band atau mendekati = lebih bagus
        if (currentPrice.compareTo(bbLower) <= 0) {
            // Harga sudah di bawah/sama dengan lower band = PERFECT
            score += 35;
            filters.add(SignalFilter.pass("BB_POSITION",
                    String.format("+35pts | Price $%.2f ≤ Lower BB $%.2f (extreme oversold zone ✅)",
                            currentPrice.doubleValue(), bbLower.doubleValue())));
        } else {
            // Harga di atas lower band tapi dalam 1% → masih ok
            // S3: Bullish candle — hanya valid kalau harga dekat lower band
            BigDecimal gap = currentPrice.subtract(bbLower);
            BigDecimal gapPct = gap.divide(currentPrice, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            double gapPctVal = gapPct.doubleValue();

            if (currentPrice.compareTo(bbLower) <= 0) {
                // Harga di bawah lower band = bounce yang kuat
                score += 15;
                filters.add(SignalFilter.pass("BULLISH_CANDLE",
                        String.format("+15pts | Bounce from below lower band $%.2f ✅",
                                bbLower.doubleValue())));
            } else if (gapPctVal <= 0.5) {
                // Harga sedikit di atas lower band, tetap valid
                score += 10;
                filters.add(SignalFilter.pass("BULLISH_CANDLE",
                        String.format("+10pts | Close near lower band (%.2f%% above) ✅", gapPctVal)));
            } else {
                // Harga jauh dari lower band, candle tidak bermakna untuk reversal
                score += 0;
                filters.add(SignalFilter.fail("BULLISH_CANDLE",
                        String.format("+0pts | Price %.2f%% above lower band (not a valid bounce)",
                                gapPctVal)));
            }
        }

        // S2: RSI scoring (oversold = lebih bagus)
        double rsi = snapshot.getRsi().doubleValue();
        if (rsi < rsiOversoldThreshold) {
            // RSI < 30 = oversold = perfect
            score += 30;
            filters.add(SignalFilter.pass("RSI",
                    String.format("+30pts | RSI %.2f < %.0f (oversold ✅)",
                            rsi, rsiOversoldThreshold)));
        } else if (rsi < 40) {
            score += 20;
            filters.add(SignalFilter.pass("RSI",
                    String.format("+20pts | RSI %.2f approaching oversold (< 40)", rsi)));
        } else if (rsi < 50) {
            score += 10;
            filters.add(SignalFilter.pass("RSI",
                    String.format("+10pts | RSI %.2f neutral-low (< 50)", rsi)));
        } else {
            filters.add(SignalFilter.fail("RSI",
                    String.format("+0pts | RSI %.2f too high for mean reversion entry", rsi)));
        }

        // S3: Bullish candle scoring
        boolean bullishClose = currentPrice.compareTo(bbLower) >= 0;
        if (bullishClose) {
            score += 15;
            filters.add(SignalFilter.pass("BULLISH_CANDLE",
                    String.format("+15pts | Bullish close $%.2f ≥ Lower BB $%.2f ✅",
                            currentPrice.doubleValue(), bbLower.doubleValue())));
        } else {
            filters.add(SignalFilter.fail("BULLISH_CANDLE",
                    String.format("+0pts | No bullish close (price $%.2f < BB $%.2f)",
                            currentPrice.doubleValue(), bbLower.doubleValue())));
        }

        // S4: Volume scoring
        double volRatio = snapshot.getVolumeRatio().doubleValue();
        if (volRatio >= 1.5) {
            score += 15;
            filters.add(SignalFilter.pass("VOLUME",
                    String.format("+15pts | Volume surge %.2fx (reversal confirmed ✅)", volRatio)));
        } else if (volRatio >= volumeMinMultiplier) {
            score += 10;
            filters.add(SignalFilter.pass("VOLUME",
                    String.format("+10pts | Volume ok %.2fx ≥ %.1fx ✅",
                            volRatio, volumeMinMultiplier)));
        } else {
            filters.add(SignalFilter.fail("VOLUME",
                    String.format("+0pts | Volume too low %.2fx < %.1fx",
                            volRatio, volumeMinMultiplier)));
        }

        // S5: BB width scoring (ranging = BB narrow = mean reversion lebih reliable)
        // %B antara -0.1 dan 0.3 = deep in lower zone
        // S5: %B scoring — harga harus di zona bawah
        if (percentB < 0) {
            // Harga di bawah lower band = perfect
            score += 10;
            filters.add(SignalFilter.pass("BB_PERCENT_B",
                    String.format("+10pts | %%B %.4f below lower band ✅", percentB)));
        } else if (percentB <= 0.2) {
            // Harga di lower zone
            score += 8;
            filters.add(SignalFilter.pass("BB_PERCENT_B",
                    String.format("+8pts | %%B %.4f in lower zone ✅", percentB)));
        } else if (percentB <= 0.4) {
            // Harga di lower-mid zone, ok tapi tidak ideal
            score += 3;
            filters.add(SignalFilter.pass("BB_PERCENT_B",
                    String.format("+3pts | %%B %.4f in lower-mid zone", percentB)));
        } else {
            // Harga di tengah atau atas — tidak ideal untuk BB reversal
            score -= 5;  // ← Penalty!
            filters.add(SignalFilter.fail("BB_PERCENT_B",
                    String.format("-5pts | %%B %.4f too high for reversal entry", percentB)));
        }

        // S6: ATR scoring (normal = lebih reliable)
        String volZone = snapshot.getVolatilityZone();
        if ("NORMAL".equals(volZone) || "LOW".equals(volZone)) {
            score += 10;
            filters.add(SignalFilter.pass("VOLATILITY",
                    String.format("+10pts | ATR %s ✅", volZone)));
        } else {
            score += 3;
            filters.add(SignalFilter.pass("VOLATILITY",
                    String.format("+3pts | ATR %s (elevated)", volZone)));
        }

        // ═══════════════════════════════════════
        // DECISION
        // ═══════════════════════════════════════
        score = Math.min(score, 100);  // ✅ TAMBAH INI
        log.info("📊 [BB] Score: {}/100 | Threshold: {}", score, buyScoreThreshold);

        if (score < buyScoreThreshold) {
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    String.format("Score %d < %d (need %d more points)",
                            score, buyScoreThreshold, buyScoreThreshold - score),
                    filters);
        }

        double posMultiplier = score >= strongBuyScoreThreshold ? 1.0 : 0.75;
        return buildBuySignal(snapshot, filters, score, posMultiplier);
    }

    private Signal buildBuySignal(GetIndicatorResponse snapshot,
                                  List<SignalFilter> filters,
                                  int score,
                                  double posMultiplier) {
        BigDecimal price = snapshot.getCurrentPrice();
        BigDecimal atr = snapshot.getAtr();
        BigDecimal bbLower = snapshot.getBbLower();
        BigDecimal bbMiddle = snapshot.getBbMiddle();

        // SL = Lower BB - (0.5 × ATR)
        BigDecimal stopLoss = bbLower.subtract(
                atr.multiply(BigDecimal.valueOf(slAtrMultiplier)));

        // TP = Middle BB
        BigDecimal takeProfit = bbMiddle;

        BigDecimal slDistance = price.subtract(stopLoss);
        BigDecimal slDistancePct = slDistance
                .divide(price, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        BigDecimal tpDistance = takeProfit.subtract(price).abs();
        BigDecimal tpDistancePct = tpDistance
                .divide(price, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        BigDecimal rrRatio = BigDecimal.ZERO;
        if (slDistance.compareTo(BigDecimal.ZERO) > 0) {
            rrRatio = tpDistance.divide(slDistance, 2, RoundingMode.HALF_UP);
        }

        BigDecimal riskAmount = BigDecimal.valueOf(modal * riskPerTradePercent / 100);
        BigDecimal calculatedPos = BigDecimal.ZERO;
        if (slDistancePct.compareTo(BigDecimal.ZERO) > 0) {
            calculatedPos = riskAmount.divide(
                    slDistancePct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP),
                    2, RoundingMode.HALF_UP);
        }

        BigDecimal maxPos = BigDecimal.valueOf(modal * maxPositionPercent / 100)
                .multiply(BigDecimal.valueOf(posMultiplier));
        BigDecimal positionSize = calculatedPos.min(maxPos);

        if (calculatedPos.compareTo(maxPos) > 0) {
            log.info("⚠️ Position capped: ${} → ${}", calculatedPos, positionSize);
        }

        String signalType = score >= strongBuyScoreThreshold ? "🚀 STRONG BUY" : "✅ BUY";
        String summary = String.format(
                "%s BB | Score: %d/100 | Price: %.4f | SL: %.4f (-%.2f%%) | TP: %.4f (+%.2f%%) | R:R 1:%.2f | Pos: $%.2f",
                signalType, score,
                price.doubleValue(), stopLoss.doubleValue(),
                slDistancePct.doubleValue(), takeProfit.doubleValue(),
                tpDistancePct.doubleValue(), rrRatio.doubleValue(),
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
                .timestamp(ZonedDateTime.now(ZoneId.of("Asia/Jakarta")).toInstant())
                .build();
    }

    public List<SignalFilter> evaluateAllFilters(GetIndicatorResponse snapshot) {
        Signal s = evaluate(snapshot);
        return s.getFilters() != null ? s.getFilters() : new ArrayList<>();
    }

    @Override
    public String getStrategyName() {
        return "BB_MEAN_REVERSION";
    }
}
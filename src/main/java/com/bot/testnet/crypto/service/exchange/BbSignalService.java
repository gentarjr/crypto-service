package com.bot.testnet.crypto.service.exchange;

import com.bot.testnet.crypto.model.dto.*;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import com.bot.testnet.crypto.service.indicator.CandlePatternHelper;
import com.bot.testnet.crypto.service.indicator.MultiTimeframeService;
import com.bot.testnet.crypto.service.indicator.SentimentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Log4j2
public class BbSignalService implements SignalService {
    private final BalanceService balanceService;
    private final SentimentService sentimentService;
    private final CandlePatternHelper candlePatternHelper;
    private final MultiTimeframeService multiTimeframeService;
    private final CandleService candleService;

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

    @Value("${trading.strategy.bb.buy-score-threshold:60}")
    private int buyScoreThreshold;

    @Value("${trading.strategy.bb.strong-buy-score-threshold:80}")
    private int strongBuyScoreThreshold;

    @Value("${trading.risk.modal:300}")
    private double modal;

    @Value("${trading.risk.risk-per-trade-percent:1.0}")
    private double riskPerTradePercent;

    @Value("${trading.risk.max-position-percent:90.0}")
    private double maxPositionPercent;

    @Value("${trading.strategy.bb.tp-atr-multiplier:1.0}")
    private double tpAtrMultiplier;

    @Value("${trading.strategy.bb.min-rr-ratio:0.8}")
    private double minRrRatio;

    @Value("${trading.strategy.bb.min-confluence-categories:3}")
    private int minConfluenceCategories;

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

        // M2.5 (MANDATORY BARU): 1H macro trend block — cegah band-walking
        try {
            String trend1h = multiTimeframeService.get1hTrend(candleService);
            if ("BEARISH".equals(trend1h)) {
                filters.add(SignalFilter.fail("MACRO_TREND_BLOCK",
                        "1H trend BEARISH — BB BUY diblok, ini band-walking bukan ranging ❌"));
                return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                        "1H macro trend bearish — block counter-trend entry", filters);
            }
            filters.add(SignalFilter.pass("MACRO_TREND_BLOCK",
                    String.format("1H trend %s ✅", trend1h)));
        } catch (Exception e) {
            log.warn("⚠️ [BB] Macro trend check failed, fail-open: {}", e.getMessage());
            filters.add(SignalFilter.pass("MACRO_TREND_BLOCK", "1H check unavailable — fail-open"));
        }

        List<Candle> candles = snapshot.getRecentCandles();
        if (candles != null && !candles.isEmpty()) {
            Candle lastCandle = candles.get(candles.size() - 1);
            if (!lastCandle.isBullish()) {
                filters.add(SignalFilter.fail("CANDLE_CONFIRM",
                        String.format("Candle BEARISH (open=%.2f close=%.2f) — tunggu reversal ❌",
                                lastCandle.getOpen().doubleValue(),
                                lastCandle.getClose().doubleValue())));
                return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                        "No bullish candle confirmation", filters);
            }
            filters.add(SignalFilter.pass("CANDLE_CONFIRM",
                    String.format("Candle BULLISH (open=%.2f close=%.2f) ✅",
                            lastCandle.getOpen().doubleValue(),
                            lastCandle.getClose().doubleValue())));
        } else {
            filters.add(SignalFilter.pass("CANDLE_CONFIRM", "No candle data — skip check"));
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


        // M4: Volume minimum — reversal tanpa volume = tidak reliable
        BigDecimal volRatioBd = snapshot.getVolumeRatio();
        if (volRatioBd != null) {
            double vr = volRatioBd.doubleValue();
            if (vr < 0.7) {
                filters.add(SignalFilter.fail("VOLUME_MIN",
                        String.format("Volume %.2fx < 0.7x avg — reversal tidak reliable ❌", vr)));
                return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                        "Volume too low for reliable reversal", filters);
            }
            filters.add(SignalFilter.pass("VOLUME_MIN",
                    String.format("Volume %.2fx ≥ 0.7x ✅", vr)));
        }

        int currentHourUtc = ZonedDateTime.now(ZoneOffset.UTC).getHour();
        boolean isDeadZone  = currentHourUtc >= 0 && currentHourUtc < 6;
        boolean isPreLondon = currentHourUtc >= 6 && currentHourUtc < 8;

        if (isDeadZone) {
            filters.add(SignalFilter.fail("SESSION_FILTER",
                    String.format("UTC %02d:xx — DEAD ZONE (00:00–06:00 UTC) ❌ No new positions", currentHourUtc)));
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    "Dead zone — no new BB positions (00:00–06:00 UTC)", filters);
        }

        if (isPreLondon) {
            filters.add(SignalFilter.pass("SESSION_FILTER",
                    String.format("UTC %02d:xx — Pre-London ⚠️ Elevated threshold applies", currentHourUtc)));
        } else {
            filters.add(SignalFilter.pass("SESSION_FILTER",
                    String.format("UTC %02d:xx — Active session ✅", currentHourUtc)));
        }

        BigDecimal currentPriceGate = snapshot.getCurrentPrice();
        BigDecimal bbLowerGate = snapshot.getBbLower();
        BigDecimal gapPctGate = currentPriceGate.subtract(bbLowerGate)
                .divide(currentPriceGate, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        double atrPctNow = snapshot.getAtrPercent().doubleValue();
        double dynamicGateMax = Math.max(0.3, Math.min(atrPctNow * 0.5, 0.8));

        if (gapPctGate.doubleValue() > dynamicGateMax) {
            filters.add(SignalFilter.fail("BB_POSITION_GATE",
                    String.format("Price %.2f%% above lower BB (max %.2f%% @ ATR %.2f%%) — BLOCK ❌",
                            gapPctGate.doubleValue(), dynamicGateMax, atrPctNow)));
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    "Price not at BB extreme — mean reversion premise invalid", filters);
        }
        filters.add(SignalFilter.pass("BB_POSITION_GATE",
                String.format("Price %.2f%% from lower BB ✅ (within %.2f%% gate @ ATR %.2f%%)",
                        gapPctGate.doubleValue(), dynamicGateMax, atrPctNow)));
        // ═══════════════════════════════════════
        // SCORING — menambah confidence
        // ═══════════════════════════════════════

        int confluenceGreen = 0;

        BigDecimal currentPrice = snapshot.getCurrentPrice();
        BigDecimal bbLower = snapshot.getBbLower();
        BigDecimal bbMiddle = snapshot.getBbMiddle();

        // S1: CATEGORY_POSITION — BB position scoring
        boolean positionGreen = false;
        if (currentPrice.compareTo(bbLower) <= 0) {
            score += 35;
            positionGreen = true;
            filters.add(SignalFilter.pass("BB_POSITION",
                    String.format("+35pts | Price $%.2f ≤ Lower BB $%.2f (extreme oversold ✅)",
                            currentPrice.doubleValue(), bbLower.doubleValue())));
        } else {
            BigDecimal gapPct = currentPrice.subtract(bbLower)
                    .divide(currentPrice, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            double gapPctVal = gapPct.doubleValue();

            if (gapPctVal <= dynamicGateMax * 0.6) {   // 60% dari gate max = masih "deket banget"
                score += 10;
                positionGreen = true;
                filters.add(SignalFilter.pass("BB_POSITION",
                        String.format("+10pts | Price %.2f%% above lower band ✅", gapPctVal)));
            } else {
                score += 0;
                filters.add(SignalFilter.fail("BB_POSITION",
                        String.format("+0pts | Price %.2f%% above lower band — not close enough", gapPctVal)));
            }
        }
        if (positionGreen) confluenceGreen++;

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
        List<Candle> recent = snapshot.getRecentCandles();
        if (recent != null && !recent.isEmpty()) {
            Candle last = recent.get(recent.size() - 1);
            if (last.getClose().compareTo(last.getOpen()) > 0) {
                score += 15;
                filters.add(SignalFilter.pass("BULLISH_CANDLE",
                        String.format("+15pts | Bullish candle (close $%.2f > open $%.2f) ✅",
                                last.getClose().doubleValue(), last.getOpen().doubleValue())));
            } else {
                filters.add(SignalFilter.fail("BULLISH_CANDLE",
                        "+0pts | Candle terakhir belum hijau — tunggu konfirmasi"));
            }
        } else {
            filters.add(SignalFilter.fail("BULLISH_CANDLE", "+0pts | No candle data"));
        }

        // S4: CATEGORY_VOLUME — volume scoring
        boolean volumeGreen = false;
        double volRatio = snapshot.getVolumeRatio().doubleValue();
        if (volRatio >= 1.5) {
            score += 15;
            volumeGreen = true;
            filters.add(SignalFilter.pass("VOLUME",
                    String.format("+15pts | Volume surge %.2fx (reversal confirmed ✅)", volRatio)));
        } else if (volRatio >= volumeMinMultiplier) {
            score += 10;
            volumeGreen = true;
            filters.add(SignalFilter.pass("VOLUME",
                    String.format("+10pts | Volume ok %.2fx ≥ %.1fx ✅",
                            volRatio, volumeMinMultiplier)));
        } else {
            filters.add(SignalFilter.fail("VOLUME",
                    String.format("+0pts | Volume too low %.2fx < %.1fx",
                            volRatio, volumeMinMultiplier)));
        }
        if (volumeGreen) confluenceGreen++;

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

        // S7: Social Sentiment — REVERSED LOGIC untuk BB
        if (sentimentService != null && sentimentService.isEnabled()) {

            // Hard block: market terlalu greedy untuk BB reversal
            if (sentimentService.isMarketTooGreedyForBb()) {
                filters.add(SignalFilter.fail("SENTIMENT",
                        String.format("+0pts | Extreme greed (%s, score: %d) — bad BB entry",
                                sentimentService.getSentimentLabel(),
                                sentimentService.getSentimentScore())));
                return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                        "Market too greedy — no reversal expected", filters);
            }

            int sentimentBonus = sentimentService.getSentimentBonusForBb();
            int sentimentScore = sentimentService.getSentimentScore();
            String sentimentLabel = sentimentService.getSentimentLabel();
            String trend = sentimentService.getTrend();
            boolean spike = sentimentService.isSocialVolumeSpike();
            double spikeChg = sentimentService.getSocialVolumeChangePercent();

            score += sentimentBonus;

            String reason = String.format(
                    "%s%dpts | %s (score: %d, F&G: %d, trend: %s%s)",
                    sentimentBonus >= 0 ? "+" : "",
                    sentimentBonus,
                    sentimentLabel,
                    sentimentScore,
                    sentimentService.getFearGreedScore(),
                    trend,
                    spike ? String.format(", 🔥spike+%.0f%%", spikeChg) : "");

            if (sentimentBonus >= 0) {
                filters.add(SignalFilter.pass("SENTIMENT", reason + (sentimentBonus > 0 ? " ✅" : "")));
            } else {
                filters.add(SignalFilter.fail("SENTIMENT", reason));
            }
            if (sentimentBonus >= 0) confluenceGreen++;
        }

        // S9: CATEGORY_REVERSAL — Candle Pattern Recognition
        boolean reversalGreen = false;
        List<Candle> recentCandles = snapshot.getRecentCandles();
        if (recentCandles != null && recentCandles.size() >= 2) {
            if (recentCandles.size() >= 3 && candlePatternHelper.isMorningStar(recentCandles)) {
                score += 20;
                reversalGreen = true;
                filters.add(SignalFilter.pass("CANDLE_PATTERN",
                        "+20pts | Morning Star pattern ✅ (strong reversal)"));
            } else if (candlePatternHelper.isBullishEngulfing(recentCandles)) {
                score += 15;
                reversalGreen = true;
                filters.add(SignalFilter.pass("CANDLE_PATTERN",
                        "+15pts | Bullish Engulfing pattern ✅"));
            } else if (candlePatternHelper.isHammer(recentCandles)) {
                score += 10;
                reversalGreen = true;
                filters.add(SignalFilter.pass("CANDLE_PATTERN",
                        "+10pts | Hammer pattern ✅"));
            } else if (candlePatternHelper.isStrongBearish(recentCandles)) {
                score -= 15;
                filters.add(SignalFilter.fail("CANDLE_PATTERN",
                        "-15pts | Strong bearish candle ❌"));
            } else if (candlePatternHelper.isDoji(recentCandles)) {
                score -= 5;
                filters.add(SignalFilter.fail("CANDLE_PATTERN",
                        "-5pts | Doji — market indecision"));
            } else {
                filters.add(SignalFilter.pass("CANDLE_PATTERN",
                        "+0pts | No notable pattern"));
            }
        } else {
            filters.add(SignalFilter.fail("CANDLE_PATTERN", "+0pts | No candle data"));
        }
        if (reversalGreen) confluenceGreen++;

        // S10: Falling Knife Memory — cek 3 candle terakhir
        if (recentCandles != null && recentCandles.size() >= 3) {
            long bearishCount = recentCandles.stream()
                    .filter(c -> c.isBearish()
                            && c.getRange().compareTo(BigDecimal.ZERO) > 0
                            && c.getBodySize()
                            .divide(c.getRange(), 4, RoundingMode.HALF_UP)
                            .compareTo(new BigDecimal("0.60")) > 0)
                    .count();

            if (bearishCount >= 2) {
                filters.add(SignalFilter.fail("FALLING_KNIFE_MEMORY",
                        String.format("+0pts | %d/3 candles strongly bearish — wait for stabilization ❌",
                                bearishCount)));
                return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                        "Recent falling knife — skip", filters);
            } else {
                filters.add(SignalFilter.pass("FALLING_KNIFE_MEMORY",
                        String.format("+0pts | Candle stability ok (%d/3 bearish)",
                                bearishCount)));
            }
        }

        // CATEGORY_MOMENTUM — Price momentum
        boolean momentumGreen = false;
        if (recentCandles != null && recentCandles.size() >= 2) {
            BigDecimal lastClose = recentCandles.get(recentCandles.size() - 1).getClose();
            BigDecimal prevClose = recentCandles.get(recentCandles.size() - 2).getClose();

            if (lastClose.compareTo(prevClose) > 0) {
                score += 10;
                momentumGreen = true;
                filters.add(SignalFilter.pass("PRICE_MOMENTUM",
                        String.format("+10pts | Price momentum UP $%.2f → $%.2f ✅",
                                prevClose.doubleValue(), lastClose.doubleValue())));
            } else if (lastClose.compareTo(prevClose) < 0) {
                score -= 10;
                filters.add(SignalFilter.fail("PRICE_MOMENTUM",
                        String.format("-10pts | Price momentum DOWN $%.2f → $%.2f ❌",
                                prevClose.doubleValue(), lastClose.doubleValue())));
            } else {
                filters.add(SignalFilter.pass("PRICE_MOMENTUM", "+0pts | Price flat"));
            }
        }
        if (momentumGreen) confluenceGreen++;

        // ═══════════════════════════════════════
        // DECISION
        // ═══════════════════════════════════════
        score = Math.min(score, 100);  // ✅ TAMBAH INI
        log.info("📊 [BB] Score: {}/100 | Threshold: {}", score, buyScoreThreshold);

        int effectiveBuyThreshold       = isPreLondon ? buyScoreThreshold + 10       : buyScoreThreshold;
        int effectiveStrongBuyThreshold  = isPreLondon ? strongBuyScoreThreshold + 10  : strongBuyScoreThreshold;

        if (score < effectiveBuyThreshold) {
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    String.format("Score %d < %d%s (need %d more points)",
                            score, effectiveBuyThreshold,
                            isPreLondon ? " [Pre-London elevated]" : "",
                            effectiveBuyThreshold - score),
                    filters);
        }

        if (confluenceGreen < minConfluenceCategories) {
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    String.format("Confluence insufficient: %d/%d categories green (need %d) ❌",
                            confluenceGreen, 5, minConfluenceCategories),
                    filters);
        }
        log.info("✅ [BB] Confluence passed: {}/{} categories green", confluenceGreen, 5);

        double posMultiplier = score >= effectiveStrongBuyThreshold ? 1.0 : 0.75;
        return buildBuySignal(snapshot, filters, score, posMultiplier);
    }

    private Signal buildBuySignal(GetIndicatorResponse snapshot,
                                  List<SignalFilter> filters,
                                  int score,
                                  double posMultiplier) {
        BigDecimal price = snapshot.getCurrentPrice();
        BigDecimal atr = snapshot.getAtr();
        BigDecimal bbMiddle = snapshot.getBbMiddle();

        BigDecimal stopLoss = price.subtract(
                atr.multiply(BigDecimal.valueOf(slAtrMultiplier)));

        // TP = Middle BB
        BigDecimal takeProfit = bbMiddle.add(
                atr.multiply(BigDecimal.valueOf(tpAtrMultiplier)));

        BigDecimal slDistance = price.subtract(stopLoss);
        if (slDistance.compareTo(BigDecimal.ZERO) <= 0) {
            filters.add(SignalFilter.fail("SL_INVALID",
                    String.format("stopLoss $%.2f ≥ price $%.2f — invalid long setup",
                            stopLoss.doubleValue(), price.doubleValue())));
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    "SL above entry — skip", filters);
        }
        BigDecimal slDistancePct = slDistance
                .divide(price, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        BigDecimal tpDistance = takeProfit.subtract(price);
        if (tpDistance.compareTo(BigDecimal.ZERO) <= 0) {
            filters.add(SignalFilter.fail("TP_INVALID",
                    String.format("takeProfit $%.2f ≤ price $%.2f — invalid long setup",
                            takeProfit.doubleValue(), price.doubleValue())));
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    "TP below entry — skip", filters);
        }
        BigDecimal tpDistancePct = tpDistance
                .divide(price, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        BigDecimal rrRatio = takeProfit.subtract(price)
                .divide(price.subtract(stopLoss), 2, RoundingMode.HALF_UP);

        BigDecimal availableCapital;
        try {
            availableCapital = balanceService.getAvailableCapital();
            if (availableCapital == null || availableCapital.compareTo(BigDecimal.ZERO) <= 0) {
                availableCapital = BigDecimal.valueOf(modal);
            }
        } catch (Exception e) {
            log.warn("Cannot fetch balance, using modal fallback");
            availableCapital = BigDecimal.valueOf(modal);
        }

        // Hitung position size TERLEBIH DAHULU — berdasarkan risk% dan jarak SL
        BigDecimal riskAmount = availableCapital
                .multiply(BigDecimal.valueOf(riskPerTradePercent / 100));

        BigDecimal calculatedPos = riskAmount.divide(
                slDistancePct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP),
                2, RoundingMode.HALF_UP);
        BigDecimal maxPos = availableCapital
                .multiply(BigDecimal.valueOf(maxPositionPercent / 100))
                .multiply(BigDecimal.valueOf(posMultiplier));
        BigDecimal positionSize = calculatedPos.min(maxPos);

        if (calculatedPos.compareTo(maxPos) > 0) {
            log.info("⚠️ Position capped: ${} → ${}", calculatedPos, positionSize);
        }

        // Fee & effective R:R dihitung dari positionSize SEBENARNYA, bukan full capital
        BigDecimal FEE_RATE = new BigDecimal("0.00075");
        BigDecimal totalFee = positionSize.multiply(FEE_RATE).multiply(BigDecimal.valueOf(2));
        BigDecimal netReward = tpDistance.divide(price, 6, RoundingMode.HALF_UP)
                .multiply(positionSize).subtract(totalFee);
        BigDecimal netRisk = slDistance.divide(price, 6, RoundingMode.HALF_UP)
                .multiply(positionSize).add(totalFee);
        BigDecimal effectiveRR = netRisk.compareTo(BigDecimal.ZERO) > 0
                ? netReward.divide(netRisk, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal minRrRatio = new BigDecimal(this.minRrRatio);
        if (effectiveRR.compareTo(minRrRatio) < 0) {
            filters.add(SignalFilter.fail("RISK_REWARD",
                    String.format("Effective R:R %.2f < %.1f after fee ($%.3f)",
                            effectiveRR.doubleValue(), minRrRatio.doubleValue(), totalFee.doubleValue())));
            return Signal.hold(StrategyType.BB_MEAN_REVERSION,
                    String.format("R:R not viable after fee: %.2f", effectiveRR.doubleValue()),
                    filters);
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
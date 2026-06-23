package com.bot.testnet.crypto.service.indicator;

import com.bot.testnet.crypto.model.dto.Candle;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import com.bot.testnet.crypto.service.exchange.CandleCacheEth;
import com.bot.testnet.crypto.service.scheduler.BarSeriesConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

@Service
@ConditionalOnProperty(name = "trading.pair-eth.enabled", havingValue = "true")
@Log4j2
@RequiredArgsConstructor
public class IndicatorServiceEth {

    private final CandleCacheEth candleCache;
    private final BarSeriesConverter converter;

    @Value("${trading.indicators.ema-fast-period:9}")
    private int emaFastPeriod;

    @Value("${trading.indicators.ema-slow-period:21}")
    private int emaSlowPeriod;

    @Value("${trading.indicators.rsi-period:14}")
    private int rsiPeriod;

    @Value("${trading.indicators.rsi-overbought:70}")
    private int rsiOverbought;

    @Value("${trading.indicators.rsi-oversold:30}")
    private int rsiOversold;

    @Value("${trading.indicators.volume-ma-period:20}")
    private int volumeMAPeriod;

    @Value("${trading.indicators.volume-surge-multiplier:1.5}")
    private double volumeSurgeMultiplier;

    @Value("${trading.indicators.volume-extreme-multiplier:2.5}")
    private double volumeExtremeMultiplier;

    @Value("${trading.indicators.volume-low-threshold:0.5}")
    private double volumeLowThreshold;

    @Value("${trading.indicators.atr-period:14}")
    private int atrPeriod;

    @Value("${trading.indicators.atr-sl-multiplier:1.5}")
    private double atrSlMultiplier;

    @Value("${trading.indicators.atr-tp-multiplier:2.0}")
    private double atrTpMultiplier;

    @Value("${trading.indicators.bb-period:20}")
    private int bbPeriod;

    @Value("${trading.indicators.bb-std-dev:2.0}")
    private double bbStdDev;

    @Value("${trading.indicators.bb-squeeze-threshold:1.5}")
    private double bbSqueezeThreshold;

    @Value("${trading.indicators.adx-period:14}")
    private int adxPeriod;

    @Value("${trading.indicators.adx-ranging-threshold:20}")
    private int adxRangingThreshold;

    @Value("${trading.indicators.adx-trending-threshold:25}")
    private int adxTrendingThreshold;

    @Value("${trading.indicators.adx-strong-trend-threshold:40}")
    private int adxStrongTrendThreshold;

    public GetIndicatorResponse calculate() {
        List<Candle> candles = candleCache.getAllCandles();

        int minRequired = Math.max(
                Math.max(emaSlowPeriod, rsiPeriod),
                Math.max(
                        Math.max(volumeMAPeriod, atrPeriod),
                        Math.max(bbPeriod, adxPeriod * 2)
                )
        ) + 5;

        if (candles.size() < minRequired) {
            log.warn("⚠️ [ETH] Not enough candles ({} < {}), cannot calculate indicators",
                    candles.size(), minRequired);
            return null;
        }

        // ✅ FIX: label diganti ke ETHUSDC (sebelumnya hardcoded BNBUSDT di file asal)
        BarSeries series = converter.convert(candles, "ETHUSDC_indicator");

        if (series.getBarCount() < minRequired) {
            log.warn("⚠️ [ETH] BarSeries too small after conversion");
            return null;
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        EMAIndicator emaFast = new EMAIndicator(closePrice, emaFastPeriod);
        EMAIndicator emaSlow = new EMAIndicator(closePrice, emaSlowPeriod);
        RSIIndicator rsi = new RSIIndicator(closePrice, rsiPeriod);
        VolumeIndicator volume = new VolumeIndicator(series);
        SMAIndicator volumeMA = new SMAIndicator(volume, volumeMAPeriod);
        ATRIndicator atr = new ATRIndicator(series, atrPeriod);
        SMAIndicator bbSma = new SMAIndicator(closePrice, bbPeriod);
        StandardDeviationIndicator bbStdDevIndicator = new StandardDeviationIndicator(closePrice, bbPeriod);
        ADXIndicator adxIndicator = new ADXIndicator(series, adxPeriod);
        PlusDIIndicator plusDIIndicator = new PlusDIIndicator(series, adxPeriod);
        MinusDIIndicator minusDIIndicator = new MinusDIIndicator(series, adxPeriod);

        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(bbSma);
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, bbStdDevIndicator, series.numOf(bbStdDev));
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, bbStdDevIndicator, series.numOf(bbStdDev));

        int lastIndex = series.getEndIndex();
        if (candleCache.isLastCandleLive() && lastIndex > 0) {
            lastIndex = lastIndex - 1;
        }
        int prevIndex = lastIndex - 1;

        Num currentClose = closePrice.getValue(lastIndex);
        Num emaFastNow = emaFast.getValue(lastIndex);
        Num emaSlowNow = emaSlow.getValue(lastIndex);
        Num emaFastPrev = emaFast.getValue(prevIndex);
        Num emaSlowPrev = emaSlow.getValue(prevIndex);
        Num rsiNow = rsi.getValue(lastIndex);
        Num currentVolumeNow = volume.getValue(lastIndex);
        Num volumeMANow = volumeMA.getValue(lastIndex);
        Num atrNow = atr.getValue(lastIndex);
        Num bbUpperNow = bbUpper.getValue(lastIndex);
        Num bbMiddleNow = bbMiddle.getValue(lastIndex);
        Num bbLowerNow = bbLower.getValue(lastIndex);
        Num adxNow = adxIndicator.getValue(lastIndex);
        Num plusDINow = plusDIIndicator.getValue(lastIndex);
        Num minusDINow = minusDIIndicator.getValue(lastIndex);

        boolean goldenCross = emaFastPrev.isLessThanOrEqual(emaSlowPrev)
                && emaFastNow.isGreaterThan(emaSlowNow);
        boolean deathCross = emaFastPrev.isGreaterThanOrEqual(emaSlowPrev)
                && emaFastNow.isLessThan(emaSlowNow);

        String trend = emaFastNow.isGreaterThan(emaSlowNow) ? "BULLISH" : "BEARISH";

        BigDecimal rsiValue = toBigDecimal(rsiNow);
        String rsiZone = classifyRsiZone(rsiValue);

        BigDecimal currentVolumeValue = toBigDecimal(currentVolumeNow);
        BigDecimal volumeMAValue = toBigDecimal(volumeMANow);
        BigDecimal volumeRatio = calculateVolumeRatio(currentVolumeValue, volumeMAValue);
        String volumeZone = classifyVolumeZone(volumeRatio);

        BigDecimal atrValue = toBigDecimal(atrNow);
        BigDecimal currentPriceBd = toBigDecimal(currentClose);
        BigDecimal atrPercent = calculateAtrPercent(atrValue, currentPriceBd);
        String volatilityZone = classifyVolatilityZone(atrPercent);

        BigDecimal bbUpperValue = toBigDecimal(bbUpperNow);
        BigDecimal bbMiddleValue = toBigDecimal(bbMiddleNow);
        BigDecimal bbLowerValue = toBigDecimal(bbLowerNow);
        BigDecimal bbWidth = calculateBbWidth(bbUpperValue, bbLowerValue, bbMiddleValue);
        BigDecimal bbPercentB = calculateBbPercentB(currentPriceBd, bbUpperValue, bbLowerValue);
        String bbZone = classifyBbZone(currentPriceBd, bbUpperValue, bbMiddleValue, bbLowerValue);
        BigDecimal adxValue = toBigDecimal(adxNow);
        BigDecimal plusDIValue = toBigDecimal(plusDINow);
        BigDecimal minusDIValue = toBigDecimal(minusDINow);
        String marketRegime = classifyMarketRegime(adxValue);
        String preferredStrategy = determinePreferredStrategy(marketRegime);

        GetIndicatorResponse snapshot = GetIndicatorResponse.builder()
                .calculatedAt(Instant.now())
                .candleTime(candles.get(lastIndex).getCloseTime())
                .currentPrice(toBigDecimal(currentClose))
                .emaFast(toBigDecimal(emaFastNow))
                .emaSlow(toBigDecimal(emaSlowNow))
                .goldenCross(goldenCross)
                .deathCross(deathCross)
                .trend(trend)
                .rsi(rsiValue)
                .rsiZone(rsiZone)
                .currentVolume(currentVolumeValue)
                .volumeMA(volumeMAValue)
                .volumeRatio(volumeRatio)
                .volumeZone(volumeZone)
                .atr(atrValue)
                .atrPercent(atrPercent)
                .volatilityZone(volatilityZone)
                .bbUpper(bbUpperValue)
                .bbMiddle(bbMiddleValue)
                .bbLower(bbLowerValue)
                .bbWidth(bbWidth)
                .bbPercentB(bbPercentB)
                .bbZone(bbZone)
                .adx(adxValue)
                .plusDI(plusDIValue)
                .minusDI(minusDIValue)
                .marketRegime(marketRegime)
                .preferredStrategy(preferredStrategy)
                .build();

        logSnapshot(snapshot);

        return snapshot;
    }

    private BigDecimal toBigDecimal(Num num) {
        return new BigDecimal(num.toString()).setScale(8, RoundingMode.HALF_UP);
    }

    private String classifyRsiZone(BigDecimal rsi) {
        if (rsi.compareTo(BigDecimal.valueOf(80)) > 0) return "EXTREME_OVERBOUGHT";
        if (rsi.compareTo(BigDecimal.valueOf(rsiOverbought)) > 0) return "OVERBOUGHT";
        if (rsi.compareTo(BigDecimal.valueOf(20)) < 0) return "EXTREME_OVERSOLD";
        if (rsi.compareTo(BigDecimal.valueOf(rsiOversold)) < 0) return "OVERSOLD";
        return "NEUTRAL";
    }

    private BigDecimal calculateVolumeRatio(BigDecimal current, BigDecimal ma) {
        if (ma == null || ma.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return current.divide(ma, 4, RoundingMode.HALF_UP);
    }

    private String classifyVolumeZone(BigDecimal ratio) {
        double ratioValue = ratio.doubleValue();

        if (ratioValue >= volumeExtremeMultiplier) return "EXTREME";
        if (ratioValue >= volumeSurgeMultiplier) return "HIGH_SURGE";
        if (ratioValue < volumeLowThreshold) return "LOW";
        return "NORMAL";
    }

    private BigDecimal calculateAtrPercent(BigDecimal atr, BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return atr.divide(price, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private String classifyVolatilityZone(BigDecimal atrPercent) {
        double value = atrPercent.doubleValue();

        if (value > 1.0) return "EXTREME";
        if (value > 0.5) return "HIGH";
        if (value < 0.2) return "LOW";
        return "NORMAL";
    }

    private BigDecimal calculateBbWidth(BigDecimal upper, BigDecimal lower, BigDecimal middle) {
        if (middle == null || middle.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return upper.subtract(lower)
                .divide(middle, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal calculateBbPercentB(BigDecimal price, BigDecimal upper, BigDecimal lower) {
        BigDecimal bandRange = upper.subtract(lower);
        if (bandRange.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(0.5);
        }
        return price.subtract(lower)
                .divide(bandRange, 4, RoundingMode.HALF_UP);
    }

    private String classifyBbZone(BigDecimal price, BigDecimal upper, BigDecimal middle, BigDecimal lower) {
        if (price.compareTo(upper) > 0) return "ABOVE_UPPER";
        if (price.compareTo(upper) == 0) return "AT_UPPER";
        if (price.compareTo(middle) > 0) return "UPPER_HALF";
        if (price.compareTo(middle) == 0) return "AT_MIDDLE";
        if (price.compareTo(lower) > 0) return "LOWER_HALF";
        if (price.compareTo(lower) == 0) return "AT_LOWER";
        return "BELOW_LOWER";
    }

    private String classifyMarketRegime(BigDecimal adx) {
        double value = adx.doubleValue();

        if (value >= adxStrongTrendThreshold) return "STRONG_TRENDING";
        if (value >= adxTrendingThreshold) return "TRENDING";
        if (value >= adxRangingThreshold) return "TRANSITION";
        return "RANGING";
    }

    private String determinePreferredStrategy(String regime) {
        return switch (regime) {
            case "STRONG_TRENDING", "TRENDING" -> "EMA_CROSSOVER";
            case "RANGING" -> "BB_MEAN_REVERSION";
            case "TRANSITION" -> "NO_TRADE";
            default -> "NO_TRADE";
        };
    }

    private void logSnapshot(GetIndicatorResponse snapshot) {
        log.info("📊 [ETH] Indicator Snapshot:");
        log.info("   Price:    {}", snapshot.getCurrentPrice());
        log.info("   EMA{}:     {} ", emaFastPeriod, snapshot.getEmaFast());
        log.info("   EMA{}:    {}", emaSlowPeriod, snapshot.getEmaSlow());
        log.info("   Trend:    {}", snapshot.getTrend());
        log.info("   RSI({}):    {} [{}]", rsiPeriod, snapshot.getRsi(), snapshot.getRsiZone());
        log.info("   Vol Ratio: {}x [{}]", snapshot.getVolumeRatio(), snapshot.getVolumeZone());
        log.info("   ATR({}):    {} ({}%) [{}]",
                atrPeriod, snapshot.getAtr(), snapshot.getAtrPercent(), snapshot.getVolatilityZone());
        log.info("   BB Width:  {}%", snapshot.getBbWidth());
        log.info("   %B:        {} [{}]", snapshot.getBbPercentB(), snapshot.getBbZone());
        log.info("   ADX({}):    {} [{}]", adxPeriod, snapshot.getAdx(), snapshot.getMarketRegime());
        log.info("   📋 Preferred Strategy: {}", snapshot.getPreferredStrategy());

        if (snapshot.isGoldenCross()) {
            log.info("   🟢 [ETH] GOLDEN CROSS detected!");
        } else if (snapshot.isDeathCross()) {
            log.info("   🔴 [ETH] DEATH CROSS detected!");
        }
    }

    public BigDecimal getEma50_4H(List<Candle> candles4h) {
        if (candles4h == null || candles4h.size() < 50) return null;

        BigDecimal multiplier = BigDecimal.valueOf(2.0 / (50 + 1));
        BigDecimal ema = candles4h.get(0).getClose();

        for (int i = 1; i < candles4h.size(); i++) {
            BigDecimal close = candles4h.get(i).getClose();
            ema = close.multiply(multiplier)
                    .add(ema.multiply(BigDecimal.ONE.subtract(multiplier)));
        }
        return ema;
    }
}
package com.bot.testnet.crypto.service.indicator;

import com.bot.testnet.crypto.model.dto.Candle;
import com.bot.testnet.crypto.model.request.GetCandleRequest;
import com.bot.testnet.crypto.model.response.GetCandleResponse;
import com.bot.testnet.crypto.service.exchange.CandleService;
import com.bot.testnet.crypto.service.scheduler.BarSeriesConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.knowm.xchange.binance.dto.marketdata.KlineInterval;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Multi-Timeframe Analysis (MTA)
 *
 * Fetch dan analyze candle di timeframe 1h
 * untuk konfirmasi signal dari 15m
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class MultiTimeframeService {

    private final BarSeriesConverter converter;

    @Value("${trading.pair.base:BNB}")
    private String baseCurrency;

    @Value("${trading.pair.quote:USDT}")
    private String quoteCurrency;

    @Value("${trading.mta.ema-period:50}")
    private int emaPeriod;

    @Value("${trading.mta.cache-duration-minutes:30}")
    private int cacheDurationMinutes;

    // Cache 1h data (tidak perlu fetch tiap 1 menit)
    private List<Candle> cachedCandles1h;
    private Instant cacheExpiry = Instant.MIN;
    private BigDecimal cachedEma50;
    private String cachedTrend;

    /**
     * Apakah 1h trend BULLISH? (price > EMA50 di 1h)
     *
     * @param candleService untuk fetch candle 1h
     * @return true kalau 1h trend bullish
     */
    public boolean is1hTrendBullish(
            com.bot.testnet.crypto.service.exchange.CandleService candleService) {
        refreshCacheIfNeeded(candleService);
        return "BULLISH".equals(cachedTrend);
    }

    /**
     * Get nilai EMA50(1h) saat ini
     */
    public BigDecimal getEma50_1h(
            com.bot.testnet.crypto.service.exchange.CandleService candleService) {
        refreshCacheIfNeeded(candleService);
        return cachedEma50;
    }

    /**
     * Get 1h trend direction
     */
    public String get1hTrend(
            com.bot.testnet.crypto.service.exchange.CandleService candleService) {
        refreshCacheIfNeeded(candleService);
        return cachedTrend;
    }

    // ═══════════════════════════════════════════════════
    // Private
    // ═══════════════════════════════════════════════════

    /**
     * Refresh cache kalau sudah expired (default: 30 menit)
     * 1h candle tidak perlu refresh tiap menit
     */
    private void refreshCacheIfNeeded(
            CandleService candleService) {

        if (Instant.now().isBefore(cacheExpiry) && cachedEma50 != null) {
            log.debug("📊 MTA: using cached 1h data (trend={})", cachedTrend);
            return;
        }

        try {
            log.info("📊 MTA: fetching fresh 1h candles...");

            // Fetch 1h candles (butuh 60+ untuk EMA50)
            var request = buildCandleRequest(KlineInterval.h1, 100);
            var response = candleService.fetchCandles(request);
            cachedCandles1h = response.getCandle();

            if (cachedCandles1h == null || cachedCandles1h.size() < emaPeriod + 5) {
                log.warn("⚠️ MTA: Not enough 1h candles ({})",
                        cachedCandles1h != null ? cachedCandles1h.size() : 0);
                cachedTrend = "UNKNOWN";
                cachedEma50 = null;
                return;
            }

            // Convert ke BarSeries
            BarSeries series = converter.convert(cachedCandles1h, "BNBUSDT_1h");

            // Hitung EMA50
            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
            EMAIndicator ema50 = new EMAIndicator(closePrice, emaPeriod);

            int lastIndex = series.getEndIndex();
            Num ema50Value = ema50.getValue(lastIndex);
            Num currentPrice = closePrice.getValue(lastIndex);

            cachedEma50 = new BigDecimal(ema50Value.toString())
                    .setScale(8, java.math.RoundingMode.HALF_UP);

            // Trend = bullish kalau price > EMA50
            cachedTrend = currentPrice.isGreaterThan(ema50Value) ? "BULLISH" : "BEARISH";

            // Update cache expiry
            cacheExpiry = Instant.now()
                    .plus(java.time.Duration.ofMinutes(cacheDurationMinutes));

            log.info("📊 MTA 1h: price={} | EMA50={} | trend={}",
                    new BigDecimal(currentPrice.toString())
                            .setScale(4, java.math.RoundingMode.HALF_UP),
                    cachedEma50.setScale(4, java.math.RoundingMode.HALF_UP),
                    cachedTrend);

        } catch (Exception e) {
            log.error("❌ MTA: Failed to fetch 1h data: {}", e.getMessage());
            cachedTrend = "UNKNOWN";
            cachedEma50 = null;
        }
    }

    private GetCandleRequest buildCandleRequest(KlineInterval interval, int limit) {
        return GetCandleRequest.builder()
                .base(baseCurrency)
                .quote(quoteCurrency)
                .interval(interval)
                .limit(limit)
                .build();
    }
}
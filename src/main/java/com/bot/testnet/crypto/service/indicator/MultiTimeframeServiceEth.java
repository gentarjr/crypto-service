package com.bot.testnet.crypto.service.indicator;

import com.bot.testnet.crypto.model.dto.Candle;
import com.bot.testnet.crypto.model.request.GetCandleRequest;
import com.bot.testnet.crypto.service.exchange.CandleService;
import com.bot.testnet.crypto.service.scheduler.BarSeriesConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.knowm.xchange.binance.dto.marketdata.KlineInterval;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
@ConditionalOnProperty(name = "trading.pair-eth.enabled", havingValue = "true")
@RequiredArgsConstructor
@Log4j2
public class MultiTimeframeServiceEth {

    private final BarSeriesConverter converter;

    @Value("${trading.pair-eth.base:ETH}")
    private String baseCurrency;

    @Value("${trading.pair-eth.quote:USDC}")
    private String quoteCurrency;

    @Value("${trading.mta-eth.ema-period:50}")
    private int emaPeriod;

    @Value("${trading.mta-eth.cache-duration-minutes:30}")
    private int cacheDurationMinutes;

    private List<Candle> cachedCandles1h;
    private Instant cacheExpiry = Instant.MIN;
    private BigDecimal cachedEma50;
    private String cachedTrend;

    public boolean is1hTrendBullish(CandleService candleService) {
        refreshCacheIfNeeded(candleService);
        return "BULLISH".equals(cachedTrend);
    }

    public BigDecimal getEma50_1h(CandleService candleService) {
        refreshCacheIfNeeded(candleService);
        return cachedEma50;
    }

    public String get1hTrend(CandleService candleService) {
        refreshCacheIfNeeded(candleService);
        return cachedTrend;
    }

    // ═══════════════════════════════════════════════════
    // Private
    // ═══════════════════════════════════════════════════

    private void refreshCacheIfNeeded(CandleService candleService) {

        if (Instant.now().isBefore(cacheExpiry) && cachedEma50 != null) {
            log.debug("📊 [ETH] MTA: using cached 1h data (trend={})", cachedTrend);
            return;
        }

        try {
            log.info("📊 [ETH] MTA: fetching fresh 1h candles...");

            var request = buildCandleRequest(KlineInterval.h1, 100);
            var response = candleService.fetchCandles(request);
            cachedCandles1h = response.getCandle();

            if (cachedCandles1h == null || cachedCandles1h.size() < emaPeriod + 5) {
                log.warn("⚠️ [ETH] MTA: Not enough 1h candles ({})",
                        cachedCandles1h != null ? cachedCandles1h.size() : 0);
                cachedTrend = "UNKNOWN";
                cachedEma50 = null;
                return;
            }

            // ✅ FIX: label BarSeries dinamis sesuai pair aktual (bukan hardcoded "BNBUSDT_1h")
            String seriesLabel = baseCurrency + quoteCurrency + "_1h";
            BarSeries series = converter.convert(cachedCandles1h, seriesLabel);

            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
            EMAIndicator ema50 = new EMAIndicator(closePrice, emaPeriod);

            int lastIndex = series.getEndIndex();
            Num ema50Value = ema50.getValue(lastIndex);
            Num currentPrice = closePrice.getValue(lastIndex);

            cachedEma50 = new BigDecimal(ema50Value.toString())
                    .setScale(8, java.math.RoundingMode.HALF_UP);

            cachedTrend = currentPrice.isGreaterThan(ema50Value) ? "BULLISH" : "BEARISH";

            cacheExpiry = Instant.now()
                    .plus(java.time.Duration.ofMinutes(cacheDurationMinutes));

            log.info("📊 [ETH] MTA 1h: price={} | EMA50={} | trend={}",
                    new BigDecimal(currentPrice.toString())
                            .setScale(4, java.math.RoundingMode.HALF_UP),
                    cachedEma50.setScale(4, java.math.RoundingMode.HALF_UP),
                    cachedTrend);

        } catch (Exception e) {
            log.error("❌ [ETH] MTA: Failed to fetch 1h data: {}", e.getMessage());
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
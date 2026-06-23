package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.model.dto.Signal;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import com.bot.testnet.crypto.service.exchange.AdaptiveSignalServiceEth;
import com.bot.testnet.crypto.service.exchange.BbSignalServiceEth;
import com.bot.testnet.crypto.service.exchange.CandleCacheEth;
import com.bot.testnet.crypto.service.exchange.CandleService;
import com.bot.testnet.crypto.service.exchange.EmaSignalServiceEth;
import com.bot.testnet.crypto.model.dto.Candle;
import com.bot.testnet.crypto.service.indicator.IndicatorServiceEth;
import com.bot.testnet.crypto.service.indicator.MultiTimeframeServiceEth;
import com.bot.testnet.crypto.service.indicator.SentimentServiceEth;
import com.bot.testnet.crypto.service.trading.OrderExecutorServiceEth;
import com.bot.testnet.crypto.service.websocket.BinanceWebSocketServiceEth;
import com.bot.testnet.crypto.service.websocket.PriceCacheEth;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnProperty(name = "trading.pair-eth.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DiagnosticsControllerEth {

    private final IndicatorServiceEth indicatorService;
    private final MultiTimeframeServiceEth multiTimeframeService;
    private final CandleService candleService;
    private final AdaptiveSignalServiceEth adaptiveSignalService;
    private final EmaSignalServiceEth emaSignalService;
    private final BbSignalServiceEth bbSignalService;
    private final CandleCacheEth candleCache;
    private final SentimentServiceEth sentimentService;
    private final OrderExecutorServiceEth orderExecutorService;
    private final BinanceWebSocketServiceEth webSocketService;
    private final PriceCacheEth priceCache;

    // ── MTA (1H trend filter) ───────────────────────────
    @GetMapping("/api/test/eth/mta/status")
    public Map<String, Object> mtaStatus() {
        String trend1h = multiTimeframeService.get1hTrend(candleService);
        BigDecimal ema50 = multiTimeframeService.getEma50_1h(candleService);

        GetIndicatorResponse snapshot = indicatorService.calculate();
        BigDecimal currentPrice = snapshot != null ? snapshot.getCurrentPrice() : null;

        return Map.of(
                "trend1h", trend1h != null ? trend1h : "N/A",
                "ema50_1h", ema50 != null ? ema50 : "N/A",
                "currentPrice", currentPrice != null ? currentPrice : "N/A",
                "priceVsEma50", (currentPrice != null && ema50 != null)
                        ? (currentPrice.compareTo(ema50) > 0 ? "ABOVE EMA50 (bullish)" : "BELOW EMA50 (bearish)")
                        : "N/A",
                "mtaBuyAllowed", "BULLISH".equals(trend1h)
        );
    }

    // ── Signal (adaptive / bb / ema) ────────────────────
    @GetMapping("/api/test/eth/signal/adaptive")
    public Signal testAdaptiveSignal() {
        GetIndicatorResponse snapshot = indicatorService.calculate();
        if (snapshot == null) return null;
        return adaptiveSignalService.evaluate(snapshot);
    }

    @GetMapping("/api/test/eth/signal/bb")
    public Signal testBbSignal() {
        GetIndicatorResponse snapshot = indicatorService.calculate();
        if (snapshot == null) return null;
        return bbSignalService.evaluate(snapshot);
    }

    @GetMapping("/api/test/eth/signal/ema")
    public Signal testEmaSignal() {
        GetIndicatorResponse snapshot = indicatorService.calculate();
        if (snapshot == null) return null;
        return emaSignalService.evaluate(snapshot);
    }

    // ── Candle cache ─────────────────────────────────────
    @GetMapping("/api/test/eth/cache/status")
    public Map<String, Object> getCacheStatus() {
        Candle latest = candleCache.getLastClosedCandle();
        return Map.of(
                "cacheSize", candleCache.size(),
                "latestClosedCandle", latest != null ? latest : "none",
                "latestClosedTime", latest != null ? latest.getCloseTime() : "none",
                "lastCandleLive", candleCache.isLastCandleLive()
        );
    }

    @GetMapping("/api/test/eth/cache/candles")
    public Map<String, Object> getCachedCandles(@RequestParam(defaultValue = "10") int n) {
        List<Candle> candles = candleCache.getLastNCandles(n);
        return Map.of(
                "totalInCache", candleCache.size(),
                "returned", candles.size(),
                "candles", candles
        );
    }

    // ── Indicators ───────────────────────────────────────
    @GetMapping("/api/test/eth/indicators")
    public GetIndicatorResponse getIndicators() {
        return indicatorService.calculate();
    }

    // ── Sentiment ────────────────────────────────────────
    @GetMapping("/api/test/eth/sentiment/status")
    public Map<String, Object> getSentimentStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", sentimentService.isEnabled());
        result.put("combinedScore", sentimentService.getSentimentScore());
        result.put("label", sentimentService.getSentimentLabel());
        result.put("trend", sentimentService.getTrend());
        result.put("fearGreedScore", sentimentService.getFearGreedScore());
        result.put("fearGreedLabel", sentimentService.getFearGreedLabel());
        result.put("interactions24h", sentimentService.getInteractions24h());
        result.put("socialVolumeSpike", sentimentService.isSocialVolumeSpike());
        result.put("spikeChangePercent",
                String.format("%.1f%%", sentimentService.getSocialVolumeChangePercent()));
        result.put("emaBonusPreview", sentimentService.getSentimentBonusForEma());
        result.put("bbBonusPreview", sentimentService.getSentimentBonusForBb());
        result.put("lastUpdated", sentimentService.getLastFetchTime());
        return result;
    }

    // ── Health (live-only, tanpa paper) ─────────────────
    @GetMapping("/api/test/eth/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "timestamp", java.time.Instant.now(),
                "paperCapital", "N/A (ETH skip paper trading)",
                "paperHalted", "N/A",
                "liveEnabled", orderExecutorService.isEnabled(),
                "liveHalted", orderExecutorService.isHalted(),
                "openPosition", orderExecutorService.getOpenPosition() != null ? "YES" : "NO"
        );
    }

    // ── WebSocket status ─────────────────────────────────
    @GetMapping("/api/ws/eth/status")
    public Map<String, Object> wsStatus() {
        return Map.of(
                "connected", webSocketService.isConnected(),
                "status", webSocketService.getStatus(),
                "latestPrice", priceCache.getLatestPrice() != null
                        ? priceCache.getLatestPrice() : "N/A",
                "isFresh", priceCache.isFresh(),
                "lastUpdate", priceCache.getLastUpdateTime() != null
                        ? priceCache.getLastUpdateTime().toString() : "N/A"
        );
    }

    // ── Paper trading (sengaja N/A) ──────────────────────
    @GetMapping("/api/test/eth/paper/status")
    public Map<String, Object> paperStatus() {
        return Map.of("message", "ETH skip paper trading — fitur ini hanya tersedia untuk BNB");
    }
}
package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.model.dto.Candle;
import com.bot.testnet.crypto.service.exchange.CandleCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Log4j2
public class CandleCacheController {

    private final CandleCache candleCache;
    /**
     * Lihat status cache: berapa candle, latest closed, dll
     */
    @GetMapping("/cache/status")
    public  Map<String, Object> getCacheStatus() {
        Candle latest = candleCache.getLastClosedCandle();
        return Map.of(
                "cacheSize", candleCache.size(),
                "latestClosedCandle", latest != null ? latest : "none",
                "latestClosedTime", latest != null ? latest.getCloseTime() : "none",
                "lastCandleLive", candleCache.isLastCandleLive()
        );
    }

    /**
     * Get N candle terakhir dari cache
     */
    @GetMapping("/cache/candles")
    public Map<String, Object> getCachedCandles(@RequestParam(defaultValue = "10") int n) {
        List<Candle> candles = candleCache.getLastNCandles(n);
        return Map.of(
                "totalInCache", candleCache.size(),
                "returned", candles.size(),
                "candles", candles
        );
    }
}

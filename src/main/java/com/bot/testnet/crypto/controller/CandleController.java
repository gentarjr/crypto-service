package com.bot.testnet.crypto.controller;


import com.bot.testnet.crypto.model.request.GetCandleRequest;
import com.bot.testnet.crypto.model.request.GetLatestCandleRequest;
import com.bot.testnet.crypto.model.response.GetCandleResponse;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import com.bot.testnet.crypto.service.exchange.CandleService;
import com.bot.testnet.crypto.service.indicator.IndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.knowm.xchange.binance.dto.marketdata.KlineInterval;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Log4j2
public class CandleController {

    private final CandleService candleService;
    private final IndicatorService indicatorService;

    @GetMapping("/candles")
    public GetCandleResponse getCandles(
            @RequestParam(defaultValue = "BNB") String base,
            @RequestParam(defaultValue = "USDT") String quote,
            @RequestParam(defaultValue = "m15") String interval,
            @RequestParam(defaultValue = "10") int limit) throws Exception{
        return candleService.fetchCandles(GetCandleRequest.builder()
                        .base(base)
                        .quote(quote)
                        .interval(KlineInterval.valueOf(interval))
                        .limit(limit)
                        .build());
    }

    /**
     * Test: Get latest candle saja
     */
    @GetMapping("/candle/latest")
    public GetCandleResponse getLatestCandle(
            @RequestParam(defaultValue = "BNB") String base,
            @RequestParam(defaultValue = "USDT") String quote,
            @RequestParam(defaultValue = "m15") String interval,
            @RequestParam(defaultValue = "10") int limit) throws Exception{
        return candleService.getLatestCandle(GetLatestCandleRequest.builder()
                .base(base)
                .quote(quote)
                .limit(limit)
                .interval(KlineInterval.valueOf(interval))
                .build());
    }

    @GetMapping("/indicators")
    public GetIndicatorResponse getIndicators() {
        return indicatorService.calculate();
    }

}

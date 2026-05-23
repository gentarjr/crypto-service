package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.model.dto.Signal;
import com.bot.testnet.crypto.model.request.GetBalanceCurrencyRequest;
import com.bot.testnet.crypto.model.request.GetCurrentPriceRequest;
import com.bot.testnet.crypto.model.request.PostBuyRequest;
import com.bot.testnet.crypto.model.request.PostSellRequest;
import com.bot.testnet.crypto.model.response.GetCurrentPriceResponse;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import com.bot.testnet.crypto.model.response.PostBuyResponse;
import com.bot.testnet.crypto.model.response.PostSellResponse;
import com.bot.testnet.crypto.service.exchange.*;
import com.bot.testnet.crypto.service.indicator.IndicatorService;
import com.bot.testnet.crypto.service.indicator.MultiTimeframeService;
import lombok.RequiredArgsConstructor;
import org.knowm.xchange.dto.account.Balance;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class BinanceController {

    private final BinanceService binanceService;
    private final BinanceBuyService binanceBuyService;
    private final BinanceSellService binanceSellService;
    private final IndicatorService indicatorService;
    private final MultiTimeframeService multiTimeframeService;
    private final CandleService candleService;
    private final AdaptiveSignalService adaptiveSignalService;

    @GetMapping("/price/{base}/{quote}")
    public GetCurrentPriceResponse getPrice(@PathVariable String base, @PathVariable String quote) {
        return binanceService.getCurrentPrice(GetCurrentPriceRequest.builder()
                        .base(base)
                        .quote(quote)
                .build());
    }

    @GetMapping("/balances")
    public List<Balance> getBalances() {
        return binanceService.getNonZeroBalances();
    }

    @GetMapping("/balance/{currency}")
    public Balance getBalanceCurrency(@PathVariable String currency){
        return binanceService.getBalance(GetBalanceCurrencyRequest.builder()
                        .currency(currency.toUpperCase())
                .build());
    }

    @PostMapping("/buy")
    public PostBuyResponse buy(
            @RequestParam String base,
            @RequestParam String quote,
            @RequestParam BigDecimal amount) throws Exception{
        return binanceBuyService.placeMarketBuyOrder(PostBuyRequest.builder()
                        .base(base)
                        .quote(quote)
                        .amount(amount)
                .build());
    }

    @PostMapping("/sell")
    public PostSellResponse sell(
            @RequestParam String base,
            @RequestParam String quote,
            @RequestParam BigDecimal amount) {
        return binanceSellService.placeMarketSellOrder(PostSellRequest.builder()
                .base(base)
                .quote(quote)
                .amount(amount)
                .build());
    }

    @GetMapping("/mta/status")
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

    @GetMapping("/signal/adaptive")
    public Signal testAdaptiveSignal(){
        GetIndicatorResponse snapshot = indicatorService.calculate();
        if (snapshot == null) return null;
        return adaptiveSignalService.evaluate(snapshot);
    }
}

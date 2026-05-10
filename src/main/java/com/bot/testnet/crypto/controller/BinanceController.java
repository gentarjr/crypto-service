package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.service.exchange.BinanceService;
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

    @GetMapping("/price/{base}/{quote}")
    public Map<String, Object> getPrice(@PathVariable String base, @PathVariable String quote) {
        return binanceService.getCurrentPrice(base, quote);
    }

    @GetMapping("/balances")
    public List<Balance> getBalances() {
        return binanceService.getNonZeroBalances();
    }

    @GetMapping("/balance/{currency}")
    public Balance getBalanceCurrency(@PathVariable String currency){
        return binanceService.getBalance(currency.toUpperCase());
    }

    @PostMapping("/buy")
    public Map<String, String> buy(
            @RequestParam String base,
            @RequestParam String quote,
            @RequestParam BigDecimal amount){
        return binanceService.placeMarketBuyOrder(base, quote, amount);
    }

    @PostMapping("/sell")
    public Map<String, String> sell(
            @RequestParam String base,
            @RequestParam String quote,
            @RequestParam BigDecimal amount) {
        return binanceService.placeMarketSellOrder(base, quote, amount);
    }

}

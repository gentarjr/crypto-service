package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.service.screener.ScreenerLivePriceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ScreenerLivePriceController {

    private final ScreenerLivePriceService screenerLivePriceService;

    @GetMapping("/api/screener/live-prices")
    public Map<String, BigDecimal> getLivePrices() {
        return screenerLivePriceService.fetchLivePricesForCurrentCandidates();
    }
}
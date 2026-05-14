package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.model.dto.Signal;
import com.bot.testnet.crypto.model.dto.SignalAction;
import com.bot.testnet.crypto.model.dto.StrategyType;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import com.bot.testnet.crypto.model.response.GetSignalTradingResponse;
import com.bot.testnet.crypto.service.exchange.BbSignalService;
import com.bot.testnet.crypto.service.exchange.EmaSignalService;
import com.bot.testnet.crypto.service.health.GetSignalTradingService;
import com.bot.testnet.crypto.service.indicator.IndicatorService;
import com.bot.testnet.crypto.service.risk.TradingHoursService;
import com.bot.testnet.crypto.service.trading.PaperTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Log4j2
public class SignalController {

    private final EmaSignalService emaSignalService;
    private final BbSignalService bbSignalService;
    private final IndicatorService indicatorService;
    private final TradingHoursService tradingHoursService;
    private final PaperTradingService paperTradingService;
    private final GetSignalTradingService getSignalTradingService;

    @GetMapping("/signal/bb")
    public Signal testBbSignal() {
        GetIndicatorResponse snapshot = indicatorService.calculate();
        if (snapshot == null) return null;
        return bbSignalService.evaluate(snapshot);
    }

    @GetMapping("/signal/ema")
    public Signal testEmaSignal() {
        GetIndicatorResponse snapshot = indicatorService.calculate();
        if (snapshot == null) return null;
        return emaSignalService.evaluate(snapshot);
    }

    @GetMapping("/trading-hours")
    public Map<String, Object> tradingHours() {
        return Map.of(
                "withinTradingHours", tradingHoursService.isWithinTradingHours(),
                "info", tradingHoursService.getTradingHoursInfo()
        );
    }

    @PostMapping("/paper/test-position")
    public Map<String, Object> testOpenPosition(
            @RequestParam BigDecimal sl,
            @RequestParam BigDecimal tp,
            @RequestParam(defaultValue = "EMA_CROSSOVER") String strategy) {

        GetIndicatorResponse snapshot = indicatorService.calculate();
        if (snapshot == null) {
            return Map.of("error", "Cannot calculate indicators");
        }

        BigDecimal currentPrice = snapshot.getCurrentPrice();

        if (sl.compareTo(currentPrice) >= 0) {
            return Map.of(
                    "error", "SL harus LEBIH KECIL dari entry price",
                    "entryPrice", currentPrice,
                    "hint", String.format("Contoh: sl=%.4f (1%% below)",
                            currentPrice.multiply(BigDecimal.valueOf(0.99)).doubleValue())
            );
        }

        if (tp.compareTo(currentPrice) <= 0) {
            return Map.of(
                    "error", "TP harus LEBIH BESAR dari entry price",
                    "entryPrice", currentPrice,
                    "hint", String.format("Contoh: tp=%.4f (2%% above)",
                            currentPrice.multiply(BigDecimal.valueOf(1.02)).doubleValue())
            );
        }

        StrategyType strategyType;
        try {
            strategyType = StrategyType.valueOf(strategy);
        } catch (Exception e) {
            strategyType = StrategyType.EMA_CROSSOVER;
        }

        try {
            Signal testSignal = Signal.builder()
                    .action(SignalAction.BUY)
                    .strategy(strategyType)
                    .price(currentPrice)
                    .stopLoss(sl)
                    .takeProfit(tp)
                    .positionSize(BigDecimal.valueOf(100))
                    .riskAmount(BigDecimal.valueOf(3))
                    .timestamp(java.time.Instant.now())
                    .build();

            paperTradingService.updateSnapshot(snapshot);
            paperTradingService.onNewCandle(testSignal, currentPrice);

            BigDecimal oneR = currentPrice.subtract(sl).abs();

            return Map.of(
                    "message", "Test position opened",
                    "strategy", strategyType,
                    "entryPrice", currentPrice,
                    "stopLoss", sl,
                    "takeProfit", tp,
                    "oneR", oneR,
                    "trailingActivatesAt",
                    String.format("$%.4f (entry + 1R)",
                            currentPrice.add(oneR).doubleValue()),
                    "exitPlan", strategyType == StrategyType.EMA_CROSSOVER
                            ? "TRAILING SL (aktif setelah profit >= 1R)"
                            : "FIXED TP at $" + tp
            );
        } catch (IllegalStateException e) {
            return Map.of("error", e.getMessage());
        }
    }

    @GetMapping("/signal/why")
    public GetSignalTradingResponse whyNotBuying() {
        return getSignalTradingService.execute();
    }
}

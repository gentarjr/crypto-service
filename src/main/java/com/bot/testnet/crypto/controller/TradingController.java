package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.model.dto.DailyStats;
import com.bot.testnet.crypto.model.dto.Signal;
import com.bot.testnet.crypto.model.dto.SignalAction;
import com.bot.testnet.crypto.model.dto.StrategyType;
import com.bot.testnet.crypto.model.dto.TradeRecord;
import com.bot.testnet.crypto.model.dto.VirtualPosition;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import com.bot.testnet.crypto.service.indicator.IndicatorService;
import com.bot.testnet.crypto.service.trading.PaperTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Log4j2
public class TradingController {

    private final PaperTradingService paperTradingService;
    private final IndicatorService indicatorService;

    @GetMapping("/paper/status")
    public Map<String, Object> paperStatus() {
        DailyStats stats = paperTradingService.getTodayStats();
        VirtualPosition pos = paperTradingService.getOpenPosition();

        return Map.of(
                "capital", paperTradingService.getCurrentCapital(),
                "isHalted", paperTradingService.isHalted(),
                "openPosition", pos != null ? pos : "none",
                "todayStats", Map.of(
                        "trades", stats.getTotalTrades(),
                        "wins", stats.getWins(),
                        "losses", stats.getLosses(),
                        "winRate", String.format("%.1f%%", stats.getWinRate()),
                        "pnl", stats.getTotalPnl(),
                        "pnlPercent", stats.getTotalPnlPercent()
                )
        );
    }

    @GetMapping("/paper/trades")
    public List<TradeRecord> paperTrades() {
        return paperTradingService.getAllTrades();
    }

    @PostMapping("/paper/test-open")
    public String testOpenPosition() {
        GetIndicatorResponse snapshot = indicatorService.calculate();
        if (snapshot == null) return "Cannot calculate indicators";

        Signal testSignal = Signal.builder()
                .action(SignalAction.BUY)
                .strategy(StrategyType.EMA_CROSSOVER)
                .price(snapshot.getCurrentPrice())
                .stopLoss(snapshot.getCurrentPrice()
                        .multiply(BigDecimal.valueOf(0.99)))  // SL -1%
                .takeProfit(snapshot.getCurrentPrice()
                        .multiply(BigDecimal.valueOf(1.015))) // TP +1.5%
                .positionSize(BigDecimal.valueOf(100))
                .riskAmount(BigDecimal.valueOf(3))
                .build();

        paperTradingService.onNewCandle(testSignal, snapshot.getCurrentPrice());
        return "Test position opened. Check /paper/status";
    }
}

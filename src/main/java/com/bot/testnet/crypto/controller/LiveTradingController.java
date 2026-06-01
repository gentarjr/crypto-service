package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.model.LivePosition;
import com.bot.testnet.crypto.model.entity.TradeHistory;
import com.bot.testnet.crypto.repository.TradeHistoryRepository;
import com.bot.testnet.crypto.service.trading.OrderExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/live")
@RequiredArgsConstructor
@Log4j2
public class LiveTradingController {

    private final OrderExecutorService orderExecutorService;
    private final TradeHistoryRepository tradeHistoryRepository;

    @GetMapping("/status")
    public Map<String, Object> status() {
        LivePosition pos = orderExecutorService.getOpenPosition();
        return Map.of(
                "enabled", orderExecutorService.isEnabled(),
                "halted", orderExecutorService.isHalted(),
                "openPosition", pos != null ? pos : "none",
                "closedCount", orderExecutorService.getClosedPositions().size()
        );
    }

    @GetMapping("/positions")
    public List<LivePosition> closedPositions() {
        return orderExecutorService.getClosedPositions();
    }

    @GetMapping("/history")
    public List<TradeHistory> getHistory() {
        return tradeHistoryRepository.findTop100ByOrderByCloseTimeDesc();
    }

    @GetMapping("/stats/today")
    public Map<String, Object> getTodayStats() {
        ZoneId wib = ZoneId.of("Asia/Jakarta");
        Instant startOfDay = LocalDate.now(wib)
                .atStartOfDay(wib).toInstant();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalTrades", tradeHistoryRepository.countTotalSince(startOfDay));
        stats.put("wins", tradeHistoryRepository.countWinsSince(startOfDay));
        stats.put("todayPnl", tradeHistoryRepository.sumPnlSince(startOfDay));
        return stats;
    }
}
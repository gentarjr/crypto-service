package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.model.dto.LivePosition;
import com.bot.testnet.crypto.model.entity.TradeHistory;
import com.bot.testnet.crypto.repository.TradeHistoryRepository;
import com.bot.testnet.crypto.service.trading.OrderExecutorServiceEth;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/live/eth")
@ConditionalOnProperty(name = "trading.pair-eth.enabled", havingValue = "true")
@RequiredArgsConstructor
@Log4j2
public class LiveTradingControllerEth {

    private final OrderExecutorServiceEth orderExecutorService;
    private final TradeHistoryRepository tradeHistoryRepository;

    @Value("${trading.risk-eth.max-consecutive-losses:10}")
    private int maxConsecutiveLosses;

    @GetMapping("/status")
    public Map<String, Object> status() {
        LivePosition pos = orderExecutorService.getOpenPosition();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", orderExecutorService.isEnabled());
        result.put("halted", orderExecutorService.isHalted());
        result.put("openPosition", pos != null ? pos : "none");
        result.put("closedCount", orderExecutorService.getClosedPositions().size());
        result.put("lastCloseTime", orderExecutorService.getLastCloseTime() != null
                ? orderExecutorService.getLastCloseTime().toString() : null);
        result.put("cooldownMinutes", orderExecutorService.getEffectiveCooldownMinutes());
        result.put("inCooldown", orderExecutorService.isInCooldown());
        result.put("cooldownRemainingMinutes", orderExecutorService.getCooldownRemainingMinutes());
        result.put("consecutiveLosses", orderExecutorService.getConsecutiveLosses());
        result.put("maxConsecutiveLosses", maxConsecutiveLosses);
        return result;
    }

    @GetMapping("/positions")
    public List<LivePosition> closedPositions() {
        return orderExecutorService.getClosedPositions();
    }

    @GetMapping("/history")
    public Map<String, Object> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<TradeHistory> result = tradeHistoryRepository
                .findByPairOrderByCloseTimeDesc("ETH", pageable);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("trades", result.getContent());
        response.put("totalElements", result.getTotalElements());
        response.put("totalPages", result.getTotalPages());
        response.put("currentPage", result.getNumber());
        response.put("pageSize", result.getSize());
        response.put("hasNext", result.hasNext());
        response.put("hasPrevious", result.hasPrevious());
        return response;
    }

    @GetMapping("/stats/today")
    public Map<String, Object> getTodayStats() {
        ZoneId wib = ZoneId.of("Asia/Jakarta");
        Instant startOfDay = LocalDate.now(wib)
                .atStartOfDay(wib).toInstant();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalTrades", tradeHistoryRepository.countTotalSinceByPair(startOfDay, "ETH"));
        stats.put("wins", tradeHistoryRepository.countWinsSinceByPair(startOfDay, "ETH"));
        stats.put("todayPnl", tradeHistoryRepository.sumPnlSinceByPair(startOfDay, "ETH"));
        return stats;
    }

    @DeleteMapping("/history/reset")
    public Map<String, String> resetHistory() {
        tradeHistoryRepository.deleteByPair("ETH");
        return Map.of("status", "OK", "message", "ETH trade history cleared");
    }

    @GetMapping("/analytics")
    public Map<String, Object> getAnalytics() {
        String pair = "ETH";
        Instant epoch = Instant.EPOCH;

        long total    = tradeHistoryRepository.countTotalSinceByPair(epoch, pair);
        long wins     = tradeHistoryRepository.countWinsSinceByPair(epoch, pair);
        long sl       = tradeHistoryRepository.countSlSinceByPair(epoch, pair);
        long tp       = tradeHistoryRepository.countTpSinceByPair(epoch, pair);
        long losses   = total - wins;

        java.math.BigDecimal totalPnl  = nvl(tradeHistoryRepository.sumPnlSinceByPair(epoch, pair));
        java.math.BigDecimal winPnl    = nvl(tradeHistoryRepository.sumWinPnlSinceByPair(epoch, pair));
        java.math.BigDecimal lossPnl   = nvl(tradeHistoryRepository.sumLossPnlSinceByPair(epoch, pair));
        Double avgDur = tradeHistoryRepository.avgDurationSinceByPair(epoch, pair);

        double wr      = total > 0 ? (double) wins / total : 0;
        double avgWin  = wins > 0 ? winPnl.doubleValue() / wins : 0;
        double avgLoss = losses > 0 ? Math.abs(lossPnl.doubleValue()) / losses : 0;
        double pf      = avgLoss > 0 ? winPnl.doubleValue() / Math.abs(lossPnl.doubleValue()) : 0;
        double rr      = avgLoss > 0 ? avgWin / avgLoss : 0;
        double exp     = (wr * avgWin) - ((1 - wr) * avgLoss);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pair", pair);
        r.put("totalTrades", total);
        r.put("wins", wins);
        r.put("losses", losses);
        r.put("winRate", wr);
        r.put("totalPnl", totalPnl);
        r.put("winPnl", winPnl);
        r.put("lossPnl", lossPnl);
        r.put("avgWin", avgWin);
        r.put("avgLoss", avgLoss);
        r.put("profitFactor", pf);
        r.put("riskRewardRatio", rr);
        r.put("expectancy", exp);
        r.put("avgDurationMinutes", avgDur != null ? avgDur : 0);
        r.put("tpCount", tp);
        r.put("slCount", sl);
        r.put("otherCount", total - tp - sl);
        return r;
    }

    private java.math.BigDecimal nvl(java.math.BigDecimal v) {
        return v != null ? v : java.math.BigDecimal.ZERO;
    }
}
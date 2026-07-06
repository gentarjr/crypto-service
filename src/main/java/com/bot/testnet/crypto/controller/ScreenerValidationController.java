package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.model.entity.ScreenerPickLog;
import com.bot.testnet.crypto.repository.ScreenerPickLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ScreenerValidationController {

    private final ScreenerPickLogRepository screenerPickLogRepository;

    @GetMapping("/api/screener/validation-log")
    public List<ScreenerPickLog> getValidationLog() {
        return screenerPickLogRepository.findAllByOrderByPickedAtDesc();
    }

    // Ringkasan kasar: dari yang sudah checked24h, berapa % yang naik (change > 0)
    @GetMapping("/api/screener/validation-summary")
    public Map<String, Object> getValidationSummary() {
        List<ScreenerPickLog> all = screenerPickLogRepository.findAllByOrderByPickedAtDesc();

        List<ScreenerPickLog> checked24h = all.stream().filter(ScreenerPickLog::isChecked24h).toList();
        long positive24h = checked24h.stream()
                .filter(l -> l.getChangePercent24h() != null && l.getChangePercent24h().compareTo(BigDecimal.ZERO) > 0)
                .count();

        List<ScreenerPickLog> checked48h = all.stream().filter(ScreenerPickLog::isChecked48h).toList();
        long positive48h = checked48h.stream()
                .filter(l -> l.getChangePercent48h() != null && l.getChangePercent48h().compareTo(BigDecimal.ZERO) > 0)
                .count();

        return Map.of(
                "totalPicks", all.size(),
                "checked24hCount", checked24h.size(),
                "positive24hCount", positive24h,
                "hitRate24h", checked24h.isEmpty() ? 0.0 : (double) positive24h / checked24h.size() * 100,
                "checked48hCount", checked48h.size(),
                "positive48hCount", positive48h,
                "hitRate48h", checked48h.isEmpty() ? 0.0 : (double) positive48h / checked48h.size() * 100
        );
    }
}
package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.model.response.HealthStatusResponse;
import com.bot.testnet.crypto.service.health.HealthCheckService;
import com.bot.testnet.crypto.service.trading.OrderExecutorService;
import com.bot.testnet.crypto.service.trading.PaperTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Log4j2
public class HealthController {

    private final HealthCheckService healthCheckService;
    private final PaperTradingService paperTradingService;
    private final OrderExecutorService orderExecutorService;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "timestamp", Instant.now(),
                "paperCapital", paperTradingService.getCurrentCapital(),
                "paperHalted", paperTradingService.isHalted(),
                "liveEnabled", orderExecutorService.isEnabled(),
                "liveHalted", orderExecutorService.isHalted(),
                "openPosition", paperTradingService.getOpenPosition() != null ? "YES" : "NO",
                "uptime", java.lang.management.ManagementFactory
                        .getRuntimeMXBean().getUptime() / 1000 + "s"
        );
    }

    /**
     * Cek health bot trading (Binance reachable, balance OK, dll)
     * Pakai cache (refresh tiap 30 detik)
     */
    @GetMapping("/bot-health")
    public HealthStatusResponse botHealth() {
        return healthCheckService.getStatus();
    }

    /**
     * Force refresh health check (skip cache)
     */
    @GetMapping("/bot-health/refresh")
    public HealthStatusResponse botHealthRefresh() {
        return healthCheckService.forceRefresh();
    }
}

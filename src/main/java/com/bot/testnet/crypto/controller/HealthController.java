package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.model.response.HealthStatusResponse;
import com.bot.testnet.crypto.service.health.HealthCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Log4j2
public class HealthController {

    private final HealthCheckService healthCheckService;

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "OK", "service", "crypto-bot");
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

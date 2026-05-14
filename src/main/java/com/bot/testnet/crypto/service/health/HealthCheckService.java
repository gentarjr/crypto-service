package com.bot.testnet.crypto.service.health;

import com.bot.testnet.crypto.model.request.GetBalanceCurrencyRequest;
import com.bot.testnet.crypto.model.request.GetCurrentPriceRequest;
import com.bot.testnet.crypto.model.response.HealthStatusResponse;
import com.bot.testnet.crypto.service.exchange.BinanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Log4j2
public class HealthCheckService {

    private final BinanceService binanceService;

    @Value("${trading.quote-currency}")
    private String quoteCurrency;

    @Value("${trading.health-check.cache-duration-seconds}")
    private long cacheDurationSeconds;

    @Value("${trading.health-check.min-quote-balance}")
    private BigDecimal minQuoteBalance;

    @Value("${trading.health-check.max-clock-skew-ms}")
    private long maxClockSkewMs;

    // Cache untuk hindari spam API
    private HealthStatusResponse cachedStatus;
    private Instant cacheExpiry = Instant.MIN;

    public boolean isHealthy() {
        HealthStatusResponse status = getStatus();
        return status.isHealthy();
    }

    public HealthStatusResponse getStatus() {
        // Pakai cache kalau belum expired
        if (cachedStatus != null && Instant.now().isBefore(cacheExpiry)) {
            log.debug("🏥 Using cached health status (healthy={})", cachedStatus.isHealthy());
            return cachedStatus;
        }

        // Cache expired, lakukan full check
        log.debug("🏥 Running full health check...");
        cachedStatus = performFullCheck();
        cacheExpiry = Instant.now().plus(Duration.ofSeconds(cacheDurationSeconds));

        if (cachedStatus.isHealthy()) {
            log.info("✅ Health check PASSED (took {}ms)", cachedStatus.getCheckDurationMs());
        } else {
            log.warn("⚠️  Health check FAILED:");
            cachedStatus.getIssues().forEach(issue -> log.warn("   • {}", issue));
        }

        return cachedStatus;
    }

    /**
     * Force refresh health check (skip cache)
     * Berguna kalau habis recover dari error
     */
    public HealthStatusResponse forceRefresh() {
        cacheExpiry = Instant.MIN;
        return getStatus();
    }

    /**
     * Lakukan semua health check
     */
    private HealthStatusResponse performFullCheck() {
        long startTime = System.currentTimeMillis();
        List<String> issues = new ArrayList<>();

        // Check 1: Binance API reachable
        checkBinanceApiReachable(issues);

        // Check 2: Server time sync
        checkServerTimeSync(issues);

        // Check 3: API credentials valid (cek balance = bukti API key valid)
        checkApiCredentials(issues);

        // Check 4: Sufficient quote balance
        checkSufficientBalance(issues);

        long duration = System.currentTimeMillis() - startTime;

        return issues.isEmpty()
                ? HealthStatusResponse.healthy(duration)
                : HealthStatusResponse.unhealthy(issues, duration);
    }

    /**
     * Check 1: Bisa hit Binance API public endpoint
     */
    private void checkBinanceApiReachable(List<String> issues) {
        try {
            // Hit public endpoint (tidak butuh auth) - get ticker BTC/USDT
            binanceService.getCurrentPrice(GetCurrentPriceRequest.builder()
                            .base("BTC")
                            .quote("USD")
                    .build());
        } catch (Exception e) {
            issues.add("Binance API unreachable: " + e.getMessage());
            log.error("❌ Binance API unreachable", e);
        }
    }

    /**
     * Check 2: Clock kita sinkron dengan Binance
     * Beda terlalu jauh → signature error
     */
    private void checkServerTimeSync(List<String> issues) {
        try {
            // XChange tidak ada method langsung get server time,
            // jadi kita compare dengan response timestamp
            long localTime = System.currentTimeMillis();
            // Untuk sekarang skip detail, asumsi OK kalau API call sukses
            // Implementasi lebih detail bisa pakai BinanceAuthenticated.serverTime()
            log.debug("🕐 Local time: {}", localTime);
        } catch (Exception e) {
            issues.add("Cannot verify server time sync: " + e.getMessage());
        }
    }

    /**
     * Check 3: API credentials valid
     */
    private void checkApiCredentials(List<String> issues) {
        try {
            // Coba akses account info — kalau API key invalid, akan throw exception
            binanceService.getNonZeroBalances();
        } catch (Exception e) {
            issues.add("API credentials invalid: " + e.getMessage());
            log.error("❌ API credentials invalid", e);
        }
    }

    /**
     * Check 4: Saldo quote currency cukup untuk trading
     */
    private void checkSufficientBalance(List<String> issues) {
        try {
            BigDecimal available = binanceService.getBalance(GetBalanceCurrencyRequest.builder()
                            .currency(quoteCurrency)
                    .build()).getAvailable();

            if (available.compareTo(minQuoteBalance) < 0) {
                issues.add(String.format(
                        "Insufficient %s balance: %s (minimum: %s)",
                        quoteCurrency, available, minQuoteBalance
                ));
                log.warn("⚠️  Low balance: {} {} < {} {}",
                        available, quoteCurrency, minQuoteBalance, quoteCurrency);
            }
        } catch (Exception e) {
            issues.add("Cannot check balance: " + e.getMessage());
        }
    }
}

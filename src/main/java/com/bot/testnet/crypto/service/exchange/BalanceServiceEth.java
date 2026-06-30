package com.bot.testnet.crypto.service.exchange;

import com.bot.testnet.crypto.model.request.GetBalanceCurrencyRequest;
import com.bot.testnet.crypto.model.request.GetCurrentPriceRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;


@Service
@ConditionalOnProperty(name = "trading.pair-eth.enabled", havingValue = "true")
@RequiredArgsConstructor
@Log4j2
public class BalanceServiceEth {

    private final BinanceService binanceService;

    @Value("${trading.pair-eth.quote:USDC}")
    private String quoteCurrency;

    @Value("${trading.pair-eth.base:ETH}")
    private String baseCurrency;

    @Value("${trading.risk-eth.modal:300}")
    private double fallbackModal;

    /**
     * Get available USDC balance dari Binance
     * Return fallback modal kalau API gagal
     */
    public BigDecimal getAvailableCapital() {
        try {
            BigDecimal available = binanceService
                    .getBalance(GetBalanceCurrencyRequest.builder()
                            .currency(quoteCurrency)
                            .build())
                    .getAvailable();

            if (available == null) {
                log.warn("⚠️ [ETH] Balance is null, using fallback: ${}", fallbackModal);
                return BigDecimal.valueOf(fallbackModal);
            }

            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("⚠️ [ETH] Available USDC = $0 — tidak cukup untuk trading");
                return BigDecimal.ZERO;
            }

            log.debug("💰 [ETH] Available balance: ${}", available);
            return available;

        } catch (Exception e) {
            log.error("❌ [ETH] Cannot fetch balance, using fallback modal: ${} | Error: {}",
                    fallbackModal, e.getMessage());
            return BigDecimal.valueOf(fallbackModal);
        }
    }

    /**
     * Get TOTAL PORTFOLIO VALUE in USDC
     * = USDC balance (available + in order) + (ETH balance × current ETH price)
     */
    public BigDecimal getTotalCapital() {
        try {
            // 1. USDC balance (available + locked di order)
            BigDecimal usdcTotal = binanceService
                    .getBalance(GetBalanceCurrencyRequest.builder()
                            .currency(quoteCurrency)
                            .build())
                    .getTotal();

            if (usdcTotal == null) {
                usdcTotal = BigDecimal.ZERO;
            }

            // 2. ETH balance (available + locked)
            BigDecimal ethTotal = BigDecimal.ZERO;
            try {
                ethTotal = binanceService
                        .getBalance(GetBalanceCurrencyRequest.builder()
                                .currency(baseCurrency)
                                .build())
                        .getTotal();
                if (ethTotal == null) ethTotal = BigDecimal.ZERO;
            } catch (Exception e) {
                log.warn("[ETH] Cannot fetch ETH balance, treating as 0: {}", e.getMessage());
            }

            // 3. Convert ETH ke USDC value (kalau ada ETH)
            BigDecimal ethValueInUsdc = BigDecimal.ZERO;
            if (ethTotal.compareTo(new BigDecimal("0.0001")) > 0) {
                try {
                    BigDecimal ethPrice = binanceService.getCurrentPrice(
                            GetCurrentPriceRequest.builder()
                                    .base(baseCurrency)
                                    .quote(quoteCurrency)
                                    .build()).getPrice();
                    ethValueInUsdc = ethTotal.multiply(ethPrice);
                } catch (Exception e) {
                    log.warn("[ETH] Cannot fetch ETH price, using 0: {}", e.getMessage());
                }
            }

            BigDecimal totalCapital = usdcTotal.add(ethValueInUsdc);

            log.debug("💼 [ETH] Total capital: ${} (USDC: ${} + ETH value: ${})",
                    totalCapital, usdcTotal, ethValueInUsdc);

            return totalCapital;

        } catch (Exception e) {
            log.error("❌ [ETH] Cannot fetch total capital, using fallback: ${} | Error: {}",
                    fallbackModal, e.getMessage());
            return BigDecimal.valueOf(fallbackModal);
        }
    }

    public Optional<BigDecimal> getTotalCapitalSafe() {
        try {
            BigDecimal usdcTotal = binanceService
                    .getBalance(GetBalanceCurrencyRequest.builder().currency(quoteCurrency).build())
                    .getTotal();
            if (usdcTotal == null) usdcTotal = BigDecimal.ZERO;

            BigDecimal ethTotal = BigDecimal.ZERO;
            try {
                ethTotal = binanceService.getBalance(GetBalanceCurrencyRequest.builder().currency(baseCurrency).build()).getTotal();
                if (ethTotal == null) ethTotal = BigDecimal.ZERO;
            } catch (Exception e) {
                log.warn("[ETH] Cannot fetch ETH balance, treating as 0: {}", e.getMessage());
            }

            BigDecimal ethValueInUsdc = BigDecimal.ZERO;
            if (ethTotal.compareTo(new BigDecimal("0.0001")) > 0) {
                BigDecimal ethPrice = binanceService.getCurrentPrice(
                        GetCurrentPriceRequest.builder().base(baseCurrency).quote(quoteCurrency).build()).getPrice();
                ethValueInUsdc = ethTotal.multiply(ethPrice);
            }

            return java.util.Optional.of(usdcTotal.add(ethValueInUsdc));
        } catch (Exception e) {
            log.error("❌ [ETH] Cannot fetch total capital for equity tracking: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * Get available ETH balance
     */
    public BigDecimal getAvailableBnb() {
        try {
            return binanceService.getBalance(GetBalanceCurrencyRequest.builder()
                    .currency(baseCurrency)
                    .build()).getAvailable();
        } catch (Exception e) {
            log.error("[ETH] Cannot fetch ETH balance: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }
}
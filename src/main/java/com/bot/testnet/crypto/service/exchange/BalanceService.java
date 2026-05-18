package com.bot.testnet.crypto.service.exchange;

import com.bot.testnet.crypto.model.request.GetBalanceCurrencyRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Service untuk get balance real dari Binance
 * Dipakai untuk position sizing yang akurat
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class BalanceService {

    private final BinanceService binanceService;

    @Value("${trading.pair.quote:USDT}")
    private String quoteCurrency;

    @Value("${trading.risk.modal:300}")
    private double fallbackModal;  // fallback kalau API gagal

    /**
     * Get available USDT balance dari Binance
     * Return fallback modal kalau API gagal
     */
    public BigDecimal getAvailableCapital() {
        try {
            BigDecimal available = binanceService
                    .getBalance(GetBalanceCurrencyRequest.builder()
                            .currency(quoteCurrency)
                            .build())
                    .getAvailable();

            if (available == null || available.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("⚠️ Balance is 0 or null, using fallback modal: ${}", fallbackModal);
                return BigDecimal.valueOf(fallbackModal);
            }

            log.debug("💰 Available balance: ${}", available);
            return available;

        } catch (Exception e) {
            log.error("❌ Cannot fetch balance, using fallback modal: ${} | Error: {}",
                    fallbackModal, e.getMessage());
            return BigDecimal.valueOf(fallbackModal);
        }
    }

    /**
     * Get total USDT balance (available + in order)
     */
    public BigDecimal getTotalCapital() {
        try {
            return binanceService
                    .getBalance(GetBalanceCurrencyRequest.builder()
                            .currency(quoteCurrency)
                            .build())
                    .getTotal();
        } catch (Exception e) {
            log.error("❌ Cannot fetch total balance: {}", e.getMessage());
            return BigDecimal.valueOf(fallbackModal);
        }
    }

    public BigDecimal getAvailableBnb() {
        try {
            return binanceService.getBalance(GetBalanceCurrencyRequest.builder()
                    .currency("BNB")
                    .build()).getAvailable();
        } catch (Exception e) {
            log.error("Cannot fetch BNB balance: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }
}
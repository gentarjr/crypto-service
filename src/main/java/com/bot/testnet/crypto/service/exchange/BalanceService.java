package com.bot.testnet.crypto.service.exchange;

import com.bot.testnet.crypto.model.request.GetBalanceCurrencyRequest;
import com.bot.testnet.crypto.model.request.GetCurrentPriceRequest;
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

    @Value("${trading.pair.base:BNB}")
    private String baseCurrency;

    @Value("${trading.risk.modal:300}")
    private double fallbackModal;

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

            if (available == null) {
                log.warn("⚠️ Balance is null, using fallback: ${}", fallbackModal);
                return BigDecimal.valueOf(fallbackModal);
            }

            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("⚠️ Available USDT = $0 — tidak cukup untuk trading");
                return BigDecimal.ZERO;
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
     * Get TOTAL PORTFOLIO VALUE in USDT
     * = USDT balance (available + in order) + (BNB balance × current BNB price)
     *
     * ✅ FIX BUG: Sebelumnya hanya return USDT balance.
     *    Saat ada posisi BNB terbuka, balance "terlihat" kecil,
     *    sehingga 3% daily loss limit jadi sangat kecil
     *    → premature halt!
     */
    public BigDecimal getTotalCapital() {
        try {
            // 1. USDT balance (available + locked di order)
            BigDecimal usdtTotal = binanceService
                    .getBalance(GetBalanceCurrencyRequest.builder()
                            .currency(quoteCurrency)
                            .build())
                    .getTotal();

            if (usdtTotal == null) {
                usdtTotal = BigDecimal.ZERO;
            }

            // 2. BNB balance (available + locked)
            BigDecimal bnbTotal = BigDecimal.ZERO;
            try {
                bnbTotal = binanceService
                        .getBalance(GetBalanceCurrencyRequest.builder()
                                .currency(baseCurrency)
                                .build())
                        .getTotal();
                if (bnbTotal == null) bnbTotal = BigDecimal.ZERO;
            } catch (Exception e) {
                log.warn("Cannot fetch BNB balance, treating as 0: {}", e.getMessage());
            }

            // 3. Convert BNB ke USDT value (kalau ada BNB)
            BigDecimal bnbValueInUsdt = BigDecimal.ZERO;
            if (bnbTotal.compareTo(new BigDecimal("0.0001")) > 0) {
                try {
                    BigDecimal bnbPrice = binanceService.getCurrentPrice(
                            GetCurrentPriceRequest.builder()
                                    .base(baseCurrency)
                                    .quote(quoteCurrency)
                                    .build()).getPrice();
                    bnbValueInUsdt = bnbTotal.multiply(bnbPrice);
                } catch (Exception e) {
                    log.warn("Cannot fetch BNB price, using 0: {}", e.getMessage());
                }
            }

            BigDecimal totalCapital = usdtTotal.add(bnbValueInUsdt);

            log.debug("💼 Total capital: ${} (USDT: ${} + BNB value: ${})",
                    totalCapital, usdtTotal, bnbValueInUsdt);

            return totalCapital;

        } catch (Exception e) {
            log.error("❌ Cannot fetch total capital, using fallback: ${} | Error: {}",
                    fallbackModal, e.getMessage());
            return BigDecimal.valueOf(fallbackModal);
        }
    }

    /**
     * Get available BNB balance
     */
    public BigDecimal getAvailableBnb() {
        try {
            return binanceService.getBalance(GetBalanceCurrencyRequest.builder()
                    .currency(baseCurrency)
                    .build()).getAvailable();
        } catch (Exception e) {
            log.error("Cannot fetch BNB balance: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }
}
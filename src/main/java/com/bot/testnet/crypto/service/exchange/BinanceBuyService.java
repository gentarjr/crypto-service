package com.bot.testnet.crypto.service.exchange;

import com.bot.testnet.crypto.model.request.GetBalanceCurrencyRequest;
import com.bot.testnet.crypto.model.request.PostBuyRequest;
import com.bot.testnet.crypto.model.response.PostBuyResponse;
import com.bot.testnet.crypto.service.TelegramNotificationService;
import com.bot.testnet.crypto.utils.Constants;
import com.bot.testnet.crypto.utils.ConvertUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.dto.trade.MarketOrder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
@Log4j2
public class BinanceBuyService {

    private final BinanceService binanceService;
    private final Exchange binanceExchange;
    private final TelegramNotificationService telegramNotificationService;

    /**
     * Place market BUY order
     * amount = jumlah base currency yang dibeli
     */
    public PostBuyResponse placeMarketBuyOrder(PostBuyRequest request) throws Exception {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneId.of("Asia/Jakarta")));
        String timestamp = ConvertUtils.convertTimestampToString(now, Constants.DATEFORMAT_YYYYMMDDT_HHMMSSSSSZ);

        CurrencyPair pair = new CurrencyPair(request.getBase(), request.getQuote());

        log.info("Placing BUY order: {} {} @ market price", request.getAmount(), request.getBase());
        BigDecimal balanceBefore;
        try {
            balanceBefore = binanceService.getBalance(GetBalanceCurrencyRequest.builder()
                    .currency(request.getBase())
                    .build()).getTotal();
            log.info("📸 Balance before: {} {}", balanceBefore, request.getBase());
        } catch (Exception e) {
            log.error("❌ Cannot fetch balance before order", e);
            return PostBuyResponse.builder()
                    .orderId(StringUtils.EMPTY)
                    .status(Constants.ERROR_STATUS)
                    .errorMessage("Cannot fetch balance: " + e.getMessage())
                    .timestamp(timestamp)
                    .build();
        }

        String orderId;
        String exceptionMessage = StringUtils.EMPTY;
        BigDecimal normalizedAmount = request.getAmount()
                .setScale(2, RoundingMode.DOWN);
        if (request.getLimitPrice() != null
                && request.getLimitPrice().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal normalizeLimitPrice = request.getLimitPrice()
                    .setScale(2, RoundingMode.DOWN);
            LimitOrder limitOrder = new LimitOrder.Builder(Order.OrderType.BID, pair)
                    .originalAmount(normalizedAmount)
                    .limitPrice(normalizeLimitPrice)
                    .build();

            log.info("📋 Placing LIMIT BUY: {} BNB @ max ${}",
                    normalizedAmount, normalizeLimitPrice);

            try {
                orderId = binanceExchange.getTradeService().placeLimitOrder(limitOrder);
                if (orderId == null || orderId.isBlank()) {
                    log.warn("⚠️ Limit order returned null ID, falling back to market");
                    throw new Exception("Null order ID");
                }
                log.info("✅ Limit order placed: {}", orderId);
            } catch (Exception e) {
                log.warn("⚠️ Limit order failed: {} — fallback to market", e.getMessage());
                // Fallback market order
                MarketOrder market = new MarketOrder(
                        Order.OrderType.BID, normalizedAmount, pair);
                orderId = binanceExchange.getTradeService().placeMarketOrder(market);
            }
        } else {
            MarketOrder order = new MarketOrder(Order.OrderType.BID, normalizedAmount, pair);
            log.info("📋 Placing MARKET BUY: {} BNB", normalizedAmount);
            orderId = binanceExchange.getTradeService().placeMarketOrder(order);
        }

        // ✅ FIX: Retry balance check sampai 5x
// Binance limit order bisa butuh beberapa detik untuk fill
// Jangan langsung anggap FAILED kalau balance belum berubah
        BigDecimal balanceAfter = balanceBefore;
        BigDecimal balanceDiff = BigDecimal.ZERO;
        int maxRetry = 5;

        for (int i = 0; i < maxRetry; i++) {
            Thread.sleep(1500); // tunggu 1.5 detik per retry
            try {
                balanceAfter = binanceService.getBalance(
                        GetBalanceCurrencyRequest.builder()
                                .currency(request.getBase())
                                .build()).getTotal();
                balanceDiff = balanceAfter.subtract(balanceBefore);
                log.info("📸 Balance check {}/{}: before={} after={} diff={}",
                        i + 1, maxRetry, balanceBefore, balanceAfter, balanceDiff);

                if (balanceDiff.compareTo(BigDecimal.ZERO) > 0) {
                    log.info("✅ ORDER CONFIRMED FILLED after {}s", (i + 1) * 1.5);
                    break; // ✅ order fill, keluar loop
                }

                log.info("⏳ Balance not yet updated, retry {}/{}", i + 1, maxRetry);

            } catch (Exception e) {
                log.warn("⚠️ Cannot check balance retry {}/{}: {}", i + 1, maxRetry, e.getMessage());
            }
        }

        log.info("📊 Final balance diff: {} {}", balanceDiff, request.getBase());

        if (balanceDiff.compareTo(BigDecimal.ZERO) > 0) {
            return PostBuyResponse.builder()
                    .orderId(StringUtils.isNotBlank(orderId) ? orderId : Constants.NO_ORDER_ID_TESTNET)
                    .status(Constants.FILLED_STATUS)
                    .balanceBefore(balanceBefore)
                    .balanceAfter(balanceAfter)
                    .filledAmount(balanceDiff)
                    .note(StringUtils.isNotBlank(exceptionMessage) ? Constants.TESTNET_QUIRK_NOTE : null)
                    .timestamp(timestamp)
                    .build();
        } else {
            // ⚠️ Setelah 5 retry masih tidak ada perubahan balance
            // Bisa jadi limit order masih pending di Binance
            log.warn("⚠️ Order status UNCERTAIN after {}s — limit order might be pending",
                    maxRetry * 1.5);
            telegramNotificationService.sendMessage(
                    "⚠️ [LIVE] Order Status Uncertain",
                    String.format(
                            "Order dikirim tapi balance belum berubah setelah %.1fs\n\n" +
                                    "Kemungkinan:\n" +
                                    "1. Limit order masih PENDING di Binance\n" +
                                    "2. Order gagal\n\n" +
                                    "Cek manual di Binance!\n" +
                                    "OrderId: %s\n" +
                                    "⏰ %s WIB",
                            maxRetry * 1.5,
                            orderId,
                            timestamp));
            return PostBuyResponse.builder()
                    .orderId(StringUtils.EMPTY)
                    .status(Constants.FAILED_STATUS)
                    .balanceBefore(balanceBefore)
                    .balanceAfter(balanceAfter)
                    .errorMessage("Order pending or failed after " + maxRetry + " retries")
                    .timestamp(timestamp)
                    .build();
        }
    }
}

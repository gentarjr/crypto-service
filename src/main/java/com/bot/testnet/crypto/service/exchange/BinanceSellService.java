package com.bot.testnet.crypto.service.exchange;

import com.bot.testnet.crypto.model.request.GetBalanceCurrencyRequest;
import com.bot.testnet.crypto.model.request.PostSellRequest;
import com.bot.testnet.crypto.model.response.PostSellResponse;
import com.bot.testnet.crypto.utils.Constants;
import com.bot.testnet.crypto.utils.ConvertUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
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
public class BinanceSellService {

    private final Exchange binanceExchange;
    private final BinanceService binanceService;

    /**
     * Place market SELL order
     */
    public PostSellResponse placeMarketSellOrder(PostSellRequest request) throws Exception {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneId.of("Asia/Jakarta")));
        String timestamp = ConvertUtils.convertTimestampToString(now, Constants.DATEFORMAT_YYYYMMDDT_HHMMSSSSSZ);

        CurrencyPair pair = new CurrencyPair(request.getBase(), request.getQuote());
        BigDecimal normalizedAmount = request.getAmount();
        MarketOrder order = new MarketOrder(Order.OrderType.ASK, normalizedAmount, pair);

        log.info("🛒 Placing SELL order: {} {} @ market price", request.getAmount(), request.getBase());


        BigDecimal balanceBefore;
        try {
            balanceBefore = binanceService.getBalance(GetBalanceCurrencyRequest.builder()
                    .currency(request.getBase())
                    .build()).getTotal();
            log.info("📸 Balance before: {} {}", balanceBefore, request.getBase());
        } catch (Exception e) {
            log.error("❌ Cannot fetch balance before order", e);
            return PostSellResponse.builder()
                    .orderId(StringUtils.EMPTY)
                    .status(Constants.ERROR_STATUS)
                    .errorMessage("Cannot fetch balance: " + e.getMessage())
                    .timestamp(timestamp)
                    .build();
        }

        // 2. Place order
        String orderId = StringUtils.EMPTY;
        String exceptionMessage = StringUtils.EMPTY;
        try {
            orderId = binanceExchange.getTradeService().placeMarketOrder(order);
            log.info("✅ Order ID returned: {}", orderId);
        } catch (Exception e) {
            exceptionMessage = e.getMessage();
            log.warn("⚠️  Place order threw exception: {}", exceptionMessage);
            log.warn("⚠️  This might be a Binance Testnet quirk - verifying via balance check...");
        }

        // 3. Tunggu Binance update balance
        Thread.sleep(3000);

        // 4. Snapshot balance SETELAH order
        BigDecimal balanceAfter;
        try {
            balanceAfter = binanceService.getBalance(GetBalanceCurrencyRequest.builder()
                    .currency(request.getBase())
                    .build()).getTotal();
            log.info("📸 Balance after: {} {}", balanceAfter, request.getBase());
        } catch (Exception e) {
            log.error("❌ Cannot fetch balance after order", e);
            return PostSellResponse.builder()
                    .orderId(orderId)
                    .status(Constants.ERROR_STATUS)
                    .errorMessage("Cannot verify order: " + e.getMessage())
                    .balanceBefore(balanceBefore)
                    .timestamp(timestamp)
                    .build();
        }


        BigDecimal balanceDiff = balanceBefore.subtract(balanceAfter);
        log.info("📊 Balance diff (sold): {} {} (expected: ~{})", balanceDiff, request.getBase(), request.getAmount());

        // 6. Tentukan status
        if (balanceDiff.compareTo(BigDecimal.ZERO) > 0) {
            // ORDER SUKSES (balance turun = SELL berhasil)
            log.info("✅ SELL ORDER CONFIRMED FILLED");
            return PostSellResponse.builder()
                    .orderId(StringUtils.isNotBlank(orderId) ? orderId : Constants.NO_ORDER_ID_TESTNET)
                    .status(Constants.FILLED_STATUS)
                    .balanceBefore(balanceBefore)
                    .balanceAfter(balanceAfter)
                    .soldAmount(balanceDiff)  // ← field beda dengan BUY (filledAmount)
                    .note(StringUtils.isNotBlank(exceptionMessage) ? Constants.TESTNET_QUIRK_NOTE : null)
                    .timestamp(timestamp)
                    .build();
        } else {
            // ORDER GAGAL (balance tidak berubah)
            log.error("❌ SELL Order FAILED - balance did not change");
            return PostSellResponse.builder()
                    .orderId(StringUtils.EMPTY)
                    .status(Constants.FAILED_STATUS)
                    .balanceBefore(balanceBefore)
                    .balanceAfter(balanceAfter)
                    .errorMessage(StringUtils.isNotBlank(exceptionMessage) ? exceptionMessage : "Unknown error")
                    .timestamp(timestamp)
                    .build();
        }
    }
}
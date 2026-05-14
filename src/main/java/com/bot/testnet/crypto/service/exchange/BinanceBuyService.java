package com.bot.testnet.crypto.service.exchange;

import com.bot.testnet.crypto.model.request.GetBalanceCurrencyRequest;
import com.bot.testnet.crypto.model.request.PostBuyRequest;
import com.bot.testnet.crypto.model.response.PostBuyResponse;
import com.bot.testnet.crypto.utils.Constants;
import com.bot.testnet.crypto.utils.ConvertUtils;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.trade.MarketOrder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Service
@RequiredArgsConstructor
@Log4j2
public class BinanceBuyService {

    private final BinanceService binanceService;
    private final Exchange binanceExchange;

    /**
     * Place market BUY order
     * amount = jumlah base currency yang dibeli
     */
    @SneakyThrows
    public PostBuyResponse placeMarketBuyOrder(PostBuyRequest request){
        Timestamp now = new Timestamp(System.currentTimeMillis());
        String timestamp = ConvertUtils.convertTimestampToString(now, Constants.DATEFORMAT_YYYYMMDDT_HHMMSSSSSZ);

        CurrencyPair pair = new CurrencyPair(request.getBase(), request.getQuote());
        MarketOrder order = new MarketOrder(Order.OrderType.BID, request.getAmount(), pair);

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

        Thread.sleep(1000);
        BigDecimal balanceAfter;
        try {
            balanceAfter = binanceService.getBalance(GetBalanceCurrencyRequest.builder()
                    .currency(request.getBase())
                    .build()).getTotal();
            log.info("📸 Balance after: {} {}", balanceAfter, request.getBase());
        } catch (Exception e) {
            log.error("❌ Cannot fetch balance after order", e);
            return PostBuyResponse.builder()
                    .orderId(orderId)
                    .status(Constants.ERROR_STATUS)
                    .errorMessage("Cannot verify order: " + e.getMessage())
                    .balanceBefore(balanceBefore)
                    .timestamp(timestamp)
                    .build();
        }

        BigDecimal balanceDiff = balanceAfter.subtract(balanceBefore);
        log.info("📊 Balance diff: {} {} (expected: ~{})", balanceDiff, request.getBase(), request.getAmount());

        if (balanceDiff.compareTo(BigDecimal.ZERO) > 0) {
            log.info("✅ ORDER CONFIRMED FILLED");
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
            log.error("❌ Order FAILED - balance did not change");
            return PostBuyResponse.builder()
                    .orderId(StringUtils.EMPTY)
                    .status(Constants.FAILED_STATUS)
                    .balanceBefore(balanceBefore)
                    .balanceAfter(balanceAfter)
                    .errorMessage(exceptionMessage != null ? exceptionMessage : "Unknown error")
                    .timestamp(timestamp)
                    .build();
        }
    }
}

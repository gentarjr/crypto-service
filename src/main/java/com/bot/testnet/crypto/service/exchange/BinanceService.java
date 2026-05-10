package com.bot.testnet.crypto.service.exchange;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.account.AccountInfo;
import org.knowm.xchange.dto.account.Balance;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.dto.trade.MarketOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BinanceService {

    private final Exchange binanceExchange;

    @Value("${balance.symbol}")
    private String balanceSymbols;

    /**
     * Get harga terkini untuk pasangan currency
     * Contoh: getCurrentPrice("BTC", "USDT")
     */
    @SneakyThrows
    public Map<String, Object> getCurrentPrice(String base, String quote){
        CurrencyPair pair = new CurrencyPair(base, quote);
        Ticker ticker = binanceExchange.getMarketDataService().getTicker(pair);
        log.info("Current price {}/{}: {}", base, quote, ticker.getLast());
        return Map.of(
                "pair", base + "/" + quote,
                "price", ticker.getLast(),
                "timestamp", System.currentTimeMillis()
        );
    }

    /**
     * Get semua balance yang ada saldonya
     */
    @SneakyThrows
    public List<Balance> getNonZeroBalances() {
        List<String> symbol = Arrays.asList(balanceSymbols.split("\\|"));
        AccountInfo info = binanceExchange.getAccountService().getAccountInfo();
        return info.getWallet().getBalances().values().stream()
                .filter(balance -> balance.getTotal().compareTo(BigDecimal.ZERO) > 0)
                .filter(balance -> symbol.contains(balance.getCurrency().getSymbol()))
                .collect(Collectors.toList());
    }

    /**
     * Get balance untuk currency tertentu
     */
    @SneakyThrows
    public Balance getBalance(String currencyCode) {
        AccountInfo info = binanceExchange.getAccountService().getAccountInfo();
        return info.getWallet().getBalance(Currency.getInstance(currencyCode));
    }

    /**
     * Place market BUY order
     * amount = jumlah base currency yang dibeli
     */
    @SneakyThrows
    public Map<String, String> placeMarketBuyOrder(String base, String quote, BigDecimal amount){
        CurrencyPair pair = new CurrencyPair(base, quote);
        MarketOrder order = new MarketOrder(Order.OrderType.BID, amount, pair);

        log.info("Placing BUY order: {} {} @ market price", amount, base);
        try{
            String orderId = binanceExchange.getTradeService().placeMarketOrder(order);
            log.info("Order placed successfully. Order ID: {}", orderId);
            return Map.of("orderId", orderId, "status", "SUBMITTED");
        }catch (Exception e){
            log.error("error buy {}", e);
            String message = e.getMessage();
            return Map.of("orderId", null, "status", "ERROR "+message);
        }
    }

    /**
     * Place market SELL order
     */
    @SneakyThrows
    public Map<String, String> placeMarketSellOrder(String base, String quote, BigDecimal amount) {
        CurrencyPair pair = new CurrencyPair(base, quote);
        MarketOrder order = new MarketOrder(Order.OrderType.ASK, amount, pair);

        log.info("Placing SELL order: {} {} @ market price", amount, base);
        try{
            String orderId = binanceExchange.getTradeService().placeMarketOrder(order);
            log.info("Order placed successfully. Order ID: {}", orderId);
            return Map.of("orderId", orderId, "status", "SUBMITTED");
        }catch (Exception e){
            log.error("error sell {}", e);
            String message = e.getMessage();
            return Map.of("orderId", null, "status", "ERROR "+message);
        }
    }
}

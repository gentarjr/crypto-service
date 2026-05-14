package com.bot.testnet.crypto.service.exchange;

import com.bot.testnet.crypto.model.request.GetBalanceCurrencyRequest;
import com.bot.testnet.crypto.model.request.GetCurrentPriceRequest;
import com.bot.testnet.crypto.model.response.GetCurrentPriceResponse;
import com.bot.testnet.crypto.utils.Constants;
import com.bot.testnet.crypto.utils.ConvertUtils;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.account.AccountInfo;
import org.knowm.xchange.dto.account.Balance;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
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
    public GetCurrentPriceResponse getCurrentPrice(GetCurrentPriceRequest request){
        CurrencyPair pair = new CurrencyPair(request.getBase(), request.getQuote());
        Ticker ticker = binanceExchange.getMarketDataService().getTicker(pair);
        log.info("Current price {}/{}: {}", request.getBase(), request.getQuote(), ticker.getLast());

        Timestamp now = new Timestamp(System.currentTimeMillis());

        return GetCurrentPriceResponse.builder()
                .price(ticker.getLast())
                .pair(request.getBase() + "/" + request.getQuote())
                .timestamp(ConvertUtils.convertTimestampToString(now, Constants.DATEFORMAT_YYYYMMDDT_HHMMSSSSSZ))
                .build();
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
    public Balance getBalance(GetBalanceCurrencyRequest request) {
        AccountInfo info = binanceExchange.getAccountService().getAccountInfo();
        return info.getWallet().getBalance(Currency.getInstance(request.getCurrency()));
    }
}

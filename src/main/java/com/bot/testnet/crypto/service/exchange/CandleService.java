package com.bot.testnet.crypto.service.exchange;

import com.bot.testnet.crypto.model.request.GetCandleRequest;
import com.bot.testnet.crypto.model.request.GetLatestCandleRequest;
import com.bot.testnet.crypto.model.dto.Candle;
import com.bot.testnet.crypto.model.response.GetCandleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.binance.dto.marketdata.BinanceKline;
import org.knowm.xchange.binance.dto.marketdata.KlineInterval;
import org.knowm.xchange.binance.service.BinanceMarketDataService;
import org.knowm.xchange.currency.CurrencyPair;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CandleService {

    private final Exchange binanceExchange;

    /**
     * Fetch candlestick (klines) dari Binance
     *
     * @param base       e.g., "BNB"
     * @param quote      e.g., "USDT"
     * @param interval   timeframe (15m, 1h, dll)
     * @param limit      jumlah candle (max 1000, default Binance 500)
     * @return List of Candle objects (urutan: oldest to newest)
     */
    public GetCandleResponse fetchCandles(GetCandleRequest request) throws Exception{
        log.info("📊 Fetching {} candles of {}/{} @ {} timeframe",
                request.getLimit(), request.getBase(), request.getQuote(), request.getInterval());

        BinanceMarketDataService marketDataService =
                (BinanceMarketDataService) binanceExchange.getMarketDataService();

        CurrencyPair pair = new CurrencyPair(request.getBase(), request.getQuote());

        // Fetch dari Binance API
        List<BinanceKline> klines = marketDataService.klines(pair, request.getInterval(), request.getLimit(), null, null);

        // Convert ke Candle model kita
        List<Candle> candles = new ArrayList<>();
        for (BinanceKline kline : klines) {
            Candle candle = Candle.builder()
                    .openTime(Instant.ofEpochMilli(kline.getOpenTime()))
                    .closeTime(Instant.ofEpochMilli(kline.getCloseTime()))
                    .open(kline.getOpen())
                    .high(kline.getHigh())
                    .low(kline.getLow())
                    .close(kline.getClose())
                    .volume(kline.getVolume())
                    .interval(request.getInterval().name())
                    .build();
            candles.add(candle);
        }
        return GetCandleResponse.builder()
                .pair(request.getBase() + "/" + request.getQuote())
                .interval(request.getInterval().code())
                .count(candles.size())
                .candle(candles)
                .build();
    }

    /**
     * Helper: get candle terbaru (latest)
     */
    public GetCandleResponse getLatestCandle(GetLatestCandleRequest request) throws Exception{
        return fetchCandles(
                GetCandleRequest.builder()
                        .base(request.getBase())
                        .quote(request.getQuote())
                        .interval(request.getInterval())
                        .limit(request.getLimit())
                        .build());
    }

    public GetCandleResponse getCandles4H(String base, String quote) throws Exception {
        log.info("📊 Fetching 4H candles for {}/{}", base, quote);
        return fetchCandles(
                GetCandleRequest.builder()
                        .base(base)
                        .quote(quote)
                        .interval(KlineInterval.h4) // ← enum value yang benar
                        .limit(50)
                        .build());
    }
}

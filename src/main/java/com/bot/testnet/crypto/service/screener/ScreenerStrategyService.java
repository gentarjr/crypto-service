package com.bot.testnet.crypto.service.screener;

import com.bot.testnet.crypto.model.entity.CoinCandidate;
import com.bot.testnet.crypto.model.dto.Candle;
import com.bot.testnet.crypto.service.scheduler.BarSeriesConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Verdict backward: untuk top-3 coin, tarik candle historis, hitung indikator
 * GENERIC (bukan replika persis EmaSignalService/BbSignalService yang sudah
 * di-tuning dengan confluence/ADX/volume-surge/pullback dsb).
 *
 * SENGAJA TERPISAH dari IndicatorService/CandleCache — itu singleton yang
 * dipakai live trading BNB/ETH, tidak boleh disuntik data coin lain.
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class ScreenerStrategyService {

    @Qualifier("binancePublicRestClient")
    private final RestClient binancePublicRestClient;

    private final BarSeriesConverter barSeriesConverter;

    @Value("${trading.indicators.ema-fast-period:9}")
    private int emaFastPeriod;

    @Value("${trading.indicators.ema-slow-period:21}")
    private int emaSlowPeriod;

    @Value("${trading.indicators.rsi-period:14}")
    private int rsiPeriod;

    @Value("${trading.indicators.rsi-overbought:70}")
    private int rsiOverbought;

    @Value("${trading.indicators.rsi-oversold:30}")
    private int rsiOversold;

    @Value("${trading.indicators.bb-period:20}")
    private int bbPeriod;

    @Value("${trading.indicators.bb-std-dev:2.0}")
    private double bbStdDev;

    @Value("${trading.indicators.atr-period:14}")
    private int atrPeriod;

    @Value("${trading.risk.sl-atr-multiplier:1.5}")
    private double slAtrMultiplier;

    @Value("${trading.risk.tp-atr-multiplier:2.0}")
    private double tpAtrMultiplier;

    private static final int KLINE_LIMIT = 200; // cukup untuk warmup EMA21/BB20/ATR14 dengan aman
    private static final int MIN_BARS_REQUIRED = 60;

    /**
     * Mutate in-place: isi field verdict/suggestedEntry/Sl/Tp pada tiap
     * CoinCandidate di list. Dipanggil SEBELUM persistTopCandidates(),
     * supaya hasil ikut tersimpan dalam transaksi yang sama.
     */
    public void enrichWithVerdict(List<CoinCandidate> topCandidates) {
        for (CoinCandidate candidate : topCandidates) {
            try {
                evaluateOne(candidate);
            } catch (Exception e) {
                log.error("Gagal evaluasi verdict untuk {}", candidate.getSymbol(), e);
                candidate.setVerdict("EVALUATION_FAILED");
                candidate.setVerdictReason("Error saat fetch/hitung indikator: " + e.getMessage());
            }
        }
    }

    private void evaluateOne(CoinCandidate candidate) {
        List<Candle> candles = fetchKlines(candidate.getSymbol(), "15m", KLINE_LIMIT);

        if (candles.size() < MIN_BARS_REQUIRED) {
            candidate.setVerdict("INSUFFICIENT_DATA");
            candidate.setVerdictReason("Cuma " + candles.size() + " candle, butuh minimal " + MIN_BARS_REQUIRED);
            return;
        }

        BarSeries series = barSeriesConverter.convert(candles, candidate.getSymbol() + "_m15");
        int lastIndex = series.getEndIndex();

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        EMAIndicator emaFast = new EMAIndicator(closePrice, emaFastPeriod);
        EMAIndicator emaSlow = new EMAIndicator(closePrice, emaSlowPeriod);
        RSIIndicator rsi = new RSIIndicator(closePrice, rsiPeriod);
        ATRIndicator atr = new ATRIndicator(series, atrPeriod);

        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(closePrice);
        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(closePrice, bbPeriod);
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, stdDev, series.numOf(bbStdDev));

        double closeVal = closePrice.getValue(lastIndex).doubleValue();
        double emaFastVal = emaFast.getValue(lastIndex).doubleValue();
        double emaSlowVal = emaSlow.getValue(lastIndex).doubleValue();
        double rsiVal = rsi.getValue(lastIndex).doubleValue();
        double atrVal = atr.getValue(lastIndex).doubleValue();
        double bbLowerVal = bbLower.getValue(lastIndex).doubleValue();

        String verdict;
        String reason;

        // CATATAN: ini filter generic, BUKAN replika logic EmaSignalService/BbSignalService
        // yang sudah di-tuning dengan confluence categories, ADX, volume-surge, pullback.
        // Ini starting point kasar, bukan sinyal setara strategi live kamu.
        if (emaFastVal > emaSlowVal && closeVal > emaSlowVal && rsiVal < rsiOverbought) {
            verdict = "EMA_BULLISH";
            reason = String.format("EMA%d(%.4f) > EMA%d(%.4f), RSI %.1f (belum overbought)",
                    emaFastPeriod, emaFastVal, emaSlowPeriod, emaSlowVal, rsiVal);
        } else if (rsiVal < rsiOversold && closeVal <= bbLowerVal) {
            verdict = "BB_OVERSOLD_BOUNCE";
            reason = String.format("RSI %.1f (oversold), harga %.6f di/bawah BB lower %.6f",
                    rsiVal, closeVal, bbLowerVal);
        } else {
            verdict = "NO_CLEAR_SIGNAL";
            reason = String.format("EMA%d/EMA%d belum cross, RSI %.1f netral", emaFastPeriod, emaSlowPeriod, rsiVal);
        }

        candidate.setVerdict(verdict);
        candidate.setVerdictReason(reason);
        candidate.setSuggestedEntry(BigDecimal.valueOf(closeVal));
        candidate.setSuggestedSl(BigDecimal.valueOf(closeVal - (atrVal * slAtrMultiplier)));
        candidate.setSuggestedTp(BigDecimal.valueOf(closeVal + (atrVal * tpAtrMultiplier)));
    }

    @SuppressWarnings("unchecked")
    private List<Candle> fetchKlines(String symbol, String interval, int limit) {
        List<List<Object>> raw = binancePublicRestClient.get()
                .uri("/api/v3/klines?symbol={symbol}&interval={interval}&limit={limit}", symbol, interval, limit)
                .retrieve()
                .body(new ParameterizedTypeReference<List<List<Object>>>() {});

        List<Candle> candles = new ArrayList<>();
        if (raw == null) return candles;

        for (List<Object> k : raw) {
            candles.add(Candle.builder()
                    .openTime(Instant.ofEpochMilli(((Number) k.get(0)).longValue()))
                    .open(new BigDecimal(k.get(1).toString()))
                    .high(new BigDecimal(k.get(2).toString()))
                    .low(new BigDecimal(k.get(3).toString()))
                    .close(new BigDecimal(k.get(4).toString()))
                    .volume(new BigDecimal(k.get(5).toString()))
                    .closeTime(Instant.ofEpochMilli(((Number) k.get(6)).longValue()))
                    .interval("m15") // konvensi internal BarSeriesConverter, bukan format Binance "15m"
                    .build());
        }
        return candles;
    }
}
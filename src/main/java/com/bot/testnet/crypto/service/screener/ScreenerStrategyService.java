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
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
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

    @Value("${trading.indicators.adx-period:14}")
    private int adxPeriod;

    private static final double ADX_TREND_THRESHOLD = 25.0; // ADX di bawah ini dianggap choppy/sideways, bukan trending
    private static final int TIER_KUAT_THRESHOLD = 5;   // skor >=5 dari maks 7
    private static final int TIER_SEDANG_THRESHOLD = 2; // skor 2-4 = Sedang, <2 = Lemah

    // SENGAJA TIDAK reuse trading.risk.sl-atr-multiplier/tp-atr-multiplier —
    // itu ditujukan buat scalping m15 bot live, bukan buat verdict swing
    // screener ini. Angka di bawah tebakan awal, BELUM divalidasi backtest.
    private static final double SWING_SL_ATR_MULTIPLIER = 2.5;
    private static final double SWING_TP_ATR_MULTIPLIER = 4.0;

    private static final String KLINE_INTERVAL_BINANCE = "4h"; // format param Binance API
    private static final String KLINE_INTERVAL_INTERNAL = "h4"; // format token BarSeriesConverter
    private static final int KLINE_LIMIT = 200; // 200 x 4h candle = ~33 hari histori, cukup warmup EMA21/BB20/ATR14
    private static final int MIN_BARS_REQUIRED = 30; // cukup buat EMA/RSI/BB, TIDAK cukup buat ADX matang
    private static final int MIN_BARS_FOR_ADX = 60; // ADX butuh ~2x periode buat stabil, di bawah ini di-skip (bukan blok verdict total)

    // Fallback buat coin yang candle-nya kurang buat indikator teknikal
    // (biasanya coin baru listing) — TEBAKAN AWAL, belum divalidasi.
    private static final double HYPE_RELATIVE_STRENGTH_THRESHOLD = 5.0;
    private static final double HYPE_VOLUME_RATIO_THRESHOLD = 3.0;

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
        List<Candle> candles = fetchKlines(candidate.getSymbol(), KLINE_INTERVAL_BINANCE, KLINE_LIMIT);

        if (candles.size() < MIN_BARS_REQUIRED) {
            evaluateInsufficientDataFallback(candidate, candles.size());
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

        ADXIndicator adx = null;
        PlusDIIndicator plusDI = null;
        MinusDIIndicator minusDI = null;
        boolean adxDataSufficient = candles.size() >= MIN_BARS_FOR_ADX;
        if (adxDataSufficient) {
            adx = new ADXIndicator(series, adxPeriod, adxPeriod);
            plusDI = new PlusDIIndicator(series, adxPeriod);
            minusDI = new MinusDIIndicator(series, adxPeriod);
        }

        double closeVal = closePrice.getValue(lastIndex).doubleValue();
        double emaFastVal = emaFast.getValue(lastIndex).doubleValue();
        double emaSlowVal = emaSlow.getValue(lastIndex).doubleValue();
        double rsiVal = rsi.getValue(lastIndex).doubleValue();
        double atrVal = atr.getValue(lastIndex).doubleValue();
        double bbLowerVal = bbLower.getValue(lastIndex).doubleValue();
        double adxVal = adxDataSufficient ? adx.getValue(lastIndex).doubleValue() : 0;
        double plusDIVal = adxDataSufficient ? plusDI.getValue(lastIndex).doubleValue() : 0;
        double minusDIVal = adxDataSufficient ? minusDI.getValue(lastIndex).doubleValue() : 0;

        // Sistem skor poin (bukan AND-gate) — 1 kriteria lemah gak langsung
        // diskualifikasi total kalau kriteria lain kuat. Bobot di bawah TEBAKAN
        // AWAL, belum divalidasi backtest — pantau lewat validation-summary.
        int score = 0;
        List<String> reasons = new ArrayList<>();

        boolean emaBullish = emaFastVal > emaSlowVal && closeVal > emaSlowVal;
        if (emaBullish) {
            score += 2;
            reasons.add(String.format("EMA%d>EMA%d(+2)", emaFastPeriod, emaSlowPeriod));
        }

        boolean rsiOk = rsiVal < rsiOverbought;
        if (rsiOk) {
            score += 1;
            reasons.add(String.format("RSI %.0f belum overbought(+1)", rsiVal));
        }

        boolean bbBounce = rsiVal < rsiOversold && closeVal <= bbLowerVal;
        if (bbBounce) {
            score += 2;
            reasons.add("BB oversold-bounce(+2)");
        }

        // ADX cuma dikasih poin kalau data cukup matang (>=60 candle) DAN
        // TRENDING (>=25) DAN arahnya (+DI vs -DI) konfirmasi arah EMA bullish
        // di atas — bukan berdiri sendiri, biar gak kasih poin ke "trending
        // kuat tapi arahnya turun". Coin dengan data <60 candle gak akan
        // pernah dapet poin ADX — ceiling skor mereka lebih rendah, itu jujur
        // secara statistik (bukan dipaksa pakai ADX yang belum matang).
        if (!adxDataSufficient) {
            reasons.add("ADX di-skip (candle <" + MIN_BARS_FOR_ADX + ", belum matang)");
        }
        boolean adxConfirmsBullish = adxDataSufficient && adxVal >= ADX_TREND_THRESHOLD && plusDIVal > minusDIVal && emaBullish;
        if (adxConfirmsBullish) {
            score += 2;
            reasons.add(String.format("ADX %.0f trending searah(+2)", adxVal));
        }

        String verdict;
        if (score >= TIER_KUAT_THRESHOLD) verdict = "KUAT";
        else if (score >= TIER_SEDANG_THRESHOLD) verdict = "SEDANG";
        else verdict = "LEMAH";

        String reason = "Skor " + score + ": " + (reasons.isEmpty() ? "tidak ada kriteria terpenuhi" : String.join(", ", reasons));

        candidate.setVerdict(verdict);
        candidate.setVerdictReason(reason);
        candidate.setSuggestedEntry(BigDecimal.valueOf(closeVal));
        candidate.setSuggestedSl(BigDecimal.valueOf(closeVal - (atrVal * SWING_SL_ATR_MULTIPLIER)));
        candidate.setSuggestedTp(BigDecimal.valueOf(closeVal + (atrVal * SWING_TP_ATR_MULTIPLIER)));
    }

    /**
     * Dipanggil kalau candle < MIN_BARS_REQUIRED (biasanya coin baru listing).
     * Indikator teknikal (EMA/RSI/BB/ADX) gak relevan buat kasus ini — coin
     * baru gerak karena hype/sentimen, bukan pola trend yang matang.
     *
     * Fallback: pakai relativeStrengthVsBtc & volumeSpikeRatio yang UDAH ADA
     * dari CoinScreenerService (dihitung dari ticker 24h, gak butuh candle
     * historis sama sekali). Kalau momentum+volume kuat, verdict jadi
     * HYPE_UNCONFIRMED — bukan KUAT, sengaja gak trigger Telegram, karena ini
     * "gak bisa dikonfirmasi teknikal", bukan "udah divalidasi teknikal".
     */
    private void evaluateInsufficientDataFallback(CoinCandidate candidate, int candleCount) {
        BigDecimal relStrength = candidate.getRelativeStrengthVsBtc();
        BigDecimal volRatio = candidate.getVolumeSpikeRatio();

        boolean strongMomentum = relStrength != null && relStrength.doubleValue() > HYPE_RELATIVE_STRENGTH_THRESHOLD;
        boolean strongVolume = volRatio != null && volRatio.doubleValue() > HYPE_VOLUME_RATIO_THRESHOLD;

        if (strongMomentum && strongVolume) {
            candidate.setVerdict("HYPE_UNCONFIRMED");
            candidate.setVerdictReason(String.format(
                    "Cuma %d candle (belum cukup buat teknikal), tapi relative strength %.1f & volume ratio %.1f kuat — worth cek manual (berita/sosmed)",
                    candleCount, relStrength.doubleValue(), volRatio.doubleValue()));
        } else {
            candidate.setVerdict("INSUFFICIENT_DATA");
            candidate.setVerdictReason("Cuma " + candleCount + " candle, butuh minimal " + MIN_BARS_REQUIRED
                    + ", dan momentum/volume juga gak cukup kuat buat fallback hype");
        }
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
                    .interval(KLINE_INTERVAL_INTERNAL) // konvensi internal BarSeriesConverter ("h4"), bukan format Binance ("4h")
                    .build());
        }
        return candles;
    }
}
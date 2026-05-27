package com.bot.testnet.crypto.service.indicator;

import com.bot.testnet.crypto.model.dto.Candle;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Helper untuk deteksi candle pattern
 * Dipakai oleh EmaSignalService dan BbSignalService
 */
@Component
@Log4j2
public class CandlePatternHelper {

    /**
     * Deteksi Bullish Engulfing:
     * Candle sekarang hijau besar yang "menelan" candle merah sebelumnya
     * Candle[-1] bearish, Candle[0] bullish dan body lebih besar
     */
    public boolean isBullishEngulfing(List<Candle> candles) {
        if (candles == null || candles.size() < 2) return false;
        Candle prev = candles.get(candles.size() - 2);
        Candle curr = candles.get(candles.size() - 1);

        return prev.isBearish()
                && curr.isBullish()
                && curr.getOpen().compareTo(prev.getClose()) <= 0
                && curr.getClose().compareTo(prev.getOpen()) >= 0
                && curr.getBodySize().compareTo(prev.getBodySize()) > 0;
    }

    /**
     * Deteksi Hammer:
     * Body kecil di atas, ekor bawah panjang (min 2× body)
     * Sinyal reversal bullish dari support
     */
    // GANTI isHammer():
    public boolean isHammer(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return false;
        Candle c = candles.get(candles.size() - 1);

        BigDecimal body      = c.getBodySize();
        BigDecimal range     = c.getRange();

        if (range.compareTo(BigDecimal.ZERO) == 0) return false;

        // ✅ Lower wick = min(open, close) - low (lebih precise)
        BigDecimal bodyBottom = c.getOpen().min(c.getClose());
        BigDecimal bodyTop    = c.getOpen().max(c.getClose());
        BigDecimal lowerWick  = bodyBottom.subtract(c.getLow());
        BigDecimal upperWick  = c.getHigh().subtract(bodyTop);

        BigDecimal bodyRatio  = body.divide(range, 4, RoundingMode.HALF_UP);

        return bodyRatio.compareTo(new BigDecimal("0.35")) < 0
                && lowerWick.compareTo(range.multiply(new BigDecimal("0.55"))) > 0
                && upperWick.compareTo(range.multiply(new BigDecimal("0.15"))) < 0;
    }

    /**
     * Deteksi Morning Star:
     * 3 candle: bearish besar → doji/kecil → bullish besar
     * Sinyal reversal kuat
     */
    public boolean isMorningStar(List<Candle> candles) {
        if (candles == null || candles.size() < 3) return false;
        Candle c1 = candles.get(candles.size() - 3); // bearish
        Candle c2 = candles.get(candles.size() - 2); // doji/kecil
        Candle c3 = candles.get(candles.size() - 1); // bullish

        BigDecimal c1Range = c1.getRange();
        BigDecimal c3Range = c3.getRange();
        if (c1Range.compareTo(BigDecimal.ZERO) == 0) return false;
        if (c3Range.compareTo(BigDecimal.ZERO) == 0) return false;

        // c2 body < 30% dari c1 body
        boolean c2Small = c1.getBodySize().compareTo(BigDecimal.ZERO) > 0
                && c2.getBodySize().divide(c1.getBodySize(), 4, RoundingMode.HALF_UP)
                .compareTo(new BigDecimal("0.30")) < 0;

        return c1.isBearish()
                && c2Small
                && c3.isBullish()
                && c3.getClose().compareTo(c1.getOpen().add(c1.getClose())
                .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP)) > 0;
    }

    /**
     * Deteksi Bearish candle besar (warning):
     * Candle merah dengan body > 60% dari range = selling pressure tinggi
     */
    public boolean isStrongBearish(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return false;
        Candle c = candles.get(candles.size() - 1);
        if (c.getRange().compareTo(BigDecimal.ZERO) == 0) return false;

        BigDecimal bodyRatio = c.getBodySize()
                .divide(c.getRange(), 4, RoundingMode.HALF_UP);
        return c.isBearish()
                && bodyRatio.compareTo(new BigDecimal("0.60")) > 0;
    }

    /**
     * Deteksi Doji:
     * Body sangat kecil (< 10% range) = indecision
     */
    public boolean isDoji(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return false;
        Candle c = candles.get(candles.size() - 1);
        if (c.getRange().compareTo(BigDecimal.ZERO) == 0) return false;

        BigDecimal bodyRatio = c.getBodySize()
                .divide(c.getRange(), 4, RoundingMode.HALF_UP);
        return bodyRatio.compareTo(new BigDecimal("0.10")) < 0;
    }
}
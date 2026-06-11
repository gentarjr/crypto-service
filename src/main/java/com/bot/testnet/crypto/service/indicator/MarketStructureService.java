package com.bot.testnet.crypto.service.indicator;

import com.bot.testnet.crypto.model.dto.Candle;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Market Structure Analyzer
 *
 * Deteksi struktur pasar dari candle history:
 * - Swing High / Swing Low via pivot detection (N kiri + N kanan)
 * - Break of Structure (BOS) ke atas
 * - Change of Character (CHoCH) — warning reversal
 * - Bullish structure (HH + HL)
 * - Bearish structure (LH + LL) → hard block di EMA
 *
 * Hanya dipakai oleh EmaSignalService.
 */
@Service
@Log4j2
public class MarketStructureService {

    @Value("${trading.market-structure.pivot-left:3}")
    private int pivotLeft;

    @Value("${trading.market-structure.pivot-right:3}")
    private int pivotRight;

    @Value("${trading.market-structure.lookback-candles:50}")
    private int lookbackCandles;

    // ─── Enum hasil analisis ──────────────────────────

    public enum StructureResult {
        BULLISH,        // HH + HL terkonfirmasi
        BEARISH,        // LH + LL terkonfirmasi → hard block
        BREAK_OF_STRUCTURE, // harga break swing high terakhir setelah HL
        CHANGE_OF_CHARACTER, // setelah uptrend, tiba-tiba LL terbentuk
        NEUTRAL         // tidak cukup data atau tidak jelas
    }

    // ─── Inner class untuk swing point ───────────────

    private static class SwingPoint {
        final int index;
        final BigDecimal price;
        final boolean isHigh; // true = swing high, false = swing low

        SwingPoint(int index, BigDecimal price, boolean isHigh) {
            this.index = index;
            this.price = price;
            this.isHigh = isHigh;
        }

        @Override
        public String toString() {
            return String.format("%s@idx%d($%.4f)",
                    isHigh ? "SH" : "SL", index, price.doubleValue());
        }
    }

    // ─── Public API ───────────────────────────────────

    /**
     * Analisis struktur market dari list candle.
     * Candle harus urut dari oldest ke newest (index 0 = oldest).
     *
     * @param candles list candle dari CandleCache (oldest first)
     * @return StructureResult
     */
    public StructureResult analyze(List<Candle> candles) {
        if (candles == null || candles.size() < (pivotLeft + pivotRight + 2)) {
            log.debug("📐 MarketStructure: not enough candles ({}) for analysis",
                    candles == null ? 0 : candles.size());
            return StructureResult.NEUTRAL;
        }

        // Ambil N candle terakhir sebagai working window
        int fromIndex = Math.max(0, candles.size() - lookbackCandles);
        List<Candle> window = candles.subList(fromIndex, candles.size());

        // Detect semua swing points di window
        List<SwingPoint> swings = detectSwingPoints(window);

        if (swings.size() < 3) {
            log.debug("📐 MarketStructure: not enough swings ({}) detected", swings.size());
            return StructureResult.NEUTRAL;
        }

        log.debug("📐 MarketStructure: detected {} swings: {}", swings.size(), swings);

        // Analisis dari swing points yang terbentuk
        return evaluateStructure(swings, window);
    }

    // ─── Private: Pivot Detection ─────────────────────

    /**
     * Detect swing high dan swing low dari candle window.
     *
     * Swing High = candle[i].high lebih tinggi dari semua
     *              candle[i-pivotLeft..i-1] DAN candle[i+1..i+pivotRight]
     *
     * Swing Low  = candle[i].low lebih rendah dari semua
     *              candle[i-pivotLeft..i-1] DAN candle[i+1..i+pivotRight]
     *
     * Note: candle terakhir (index size-1 sampai size-pivotRight)
     * tidak bisa jadi pivot karena belum ada konfirmasi kanan.
     * Ini by design — mencegah false pivot dari candle yang belum confirmed.
     */
    private List<SwingPoint> detectSwingPoints(List<Candle> candles) {
        List<SwingPoint> result = new ArrayList<>();
        int size = candles.size();

        // Loop dari pivotLeft sampai size-pivotRight-1
        // Candle di luar range ini tidak bisa jadi pivot (tidak ada cukup neighbor)
        for (int i = pivotLeft; i < size - pivotRight; i++) {
            Candle current = candles.get(i);

            if (isSwingHigh(candles, i)) {
                result.add(new SwingPoint(i, current.getHigh(), true));
            } else if (isSwingLow(candles, i)) {
                result.add(new SwingPoint(i, current.getLow(), false));
            }
        }

        return result;
    }

    /**
     * Cek apakah candle[index] adalah swing high.
     * High-nya harus lebih tinggi dari semua candle kiri dan kanan.
     */
    private boolean isSwingHigh(List<Candle> candles, int index) {
        BigDecimal high = candles.get(index).getHigh();

        // Cek kiri
        for (int i = index - pivotLeft; i < index; i++) {
            if (candles.get(i).getHigh().compareTo(high) >= 0) {
                return false;
            }
        }

        // Cek kanan
        for (int i = index + 1; i <= index + pivotRight; i++) {
            if (candles.get(i).getHigh().compareTo(high) >= 0) {
                return false;
            }
        }

        return true;
    }

    /**
     * Cek apakah candle[index] adalah swing low.
     * Low-nya harus lebih rendah dari semua candle kiri dan kanan.
     */
    private boolean isSwingLow(List<Candle> candles, int index) {
        BigDecimal low = candles.get(index).getLow();

        // Cek kiri
        for (int i = index - pivotLeft; i < index; i++) {
            if (candles.get(i).getLow().compareTo(low) <= 0) {
                return false;
            }
        }

        // Cek kanan
        for (int i = index + 1; i <= index + pivotRight; i++) {
            if (candles.get(i).getLow().compareTo(low) <= 0) {
                return false;
            }
        }

        return true;
    }

    // ─── Private: Structure Evaluation ───────────────

    /**
     * Evaluasi struktur dari urutan swing points.
     *
     * Logika:
     * 1. Ambil 4 swing terakhir (cukup untuk determine struktur)
     * 2. Classify tiap swing sebagai HH/HL/LH/LL relatif terhadap swing sebelumnya
     * 3. Tentukan hasil berdasarkan pola
     */
    private StructureResult evaluateStructure(List<SwingPoint> swings, List<Candle> window) {
        int size = swings.size();
        SwingPoint s1 = swings.get(size - 3);
        SwingPoint s2 = swings.get(size - 2);
        SwingPoint s3 = swings.get(size - 1);

        log.debug("📐 Analyzing swings: {} → {} → {}", s1, s2, s3);

        BigDecimal currentPrice = window.get(window.size() - 1).getClose();

        // Pattern: SL → SH → SL
        if (!s1.isHigh && s2.isHigh && !s3.isHigh) {
            boolean isHL = s3.price.compareTo(s1.price) > 0;
            boolean isLL = s3.price.compareTo(s1.price) < 0;

            // BOS: HL terbentuk DAN harga sudah break di atas swing high
            if (isHL && currentPrice.compareTo(s2.price) > 0) {
                log.info("📐 BREAK_OF_STRUCTURE — HL confirmed + price broke SH ${}",
                        s2.price);
                return StructureResult.BREAK_OF_STRUCTURE;
            }

            // BULLISH: HL terbentuk tapi belum break SH
            if (isHL) {
                log.info("📐 BULLISH (HL confirmed) SL${} → SH${} → HL${}",
                        s1.price, s2.price, s3.price);
                return StructureResult.BULLISH;
            }

            // CHoCH: LL terbentuk setelah swing high
            if (isLL) {
                log.info("📐 CHANGE_OF_CHARACTER — LL after SH. SL${} → SH${} → LL${}",
                        s1.price, s2.price, s3.price);
                return StructureResult.CHANGE_OF_CHARACTER;
            }
        }

        // Pattern: SH → SL → SH
        if (s1.isHigh && !s2.isHigh && s3.isHigh) {
            boolean isHH = s3.price.compareTo(s1.price) > 0;
            boolean isLH = s3.price.compareTo(s1.price) < 0;

            if (isHH) {
                log.info("📐 BULLISH (HH confirmed) SH${} → HL${} → HH${}",
                        s1.price, s2.price, s3.price);
                return StructureResult.BULLISH;
            }

            if (isLH) {
                log.info("📐 CHANGE_OF_CHARACTER — LH forming. SH${} → SL${} → LH${}",
                        s1.price, s2.price, s3.price);
                return StructureResult.CHANGE_OF_CHARACTER;
            }
        }

        // Pattern: SH → SH → SL (dua swing high berurutan)
        if (s1.isHigh && s2.isHigh && !s3.isHigh) {
            boolean isLH = s2.price.compareTo(s1.price) < 0;
            if (isLH) {
                log.info("📐 BEARISH — LH${} → LL${}",
                        s2.price, s3.price);
                return StructureResult.BEARISH;
            }
        }

        // Pattern: SL → SH → SL (sama dengan pattern pertama tapi sudah di-handle)
        // Pattern: SL → SL → SH — dua swing low berurutan
        if (!s1.isHigh && !s2.isHigh && s3.isHigh) {
            boolean isLL = s2.price.compareTo(s1.price) < 0;
            if (isLL) {
                log.info("📐 BEARISH (LL confirmed) SL${} → LL${} → LH${}",
                        s1.price, s2.price, s3.price);
                return StructureResult.BEARISH;
            }
        }

        log.debug("📐 NEUTRAL — no clear pattern");
        return StructureResult.NEUTRAL;
    }
}
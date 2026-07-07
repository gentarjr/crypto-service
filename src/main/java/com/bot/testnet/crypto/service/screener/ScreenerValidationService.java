package com.bot.testnet.crypto.service.screener;

import com.bot.testnet.crypto.model.dto.BinancePriceDto;
import com.bot.testnet.crypto.model.entity.CoinCandidate;
import com.bot.testnet.crypto.model.entity.ScreenerPickLog;
import com.bot.testnet.crypto.repository.ScreenerPickLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Log4j2
public class ScreenerValidationService {

    private final ScreenerPickLogRepository screenerPickLogRepository;

    @Qualifier("binancePublicRestClient")
    private final RestClient binancePublicRestClient;

    private static final Duration DEDUP_WINDOW = Duration.ofHours(20); // jangan log ulang symbol yang sama dalam 20 jam
    private static final Duration CHECK_24H = Duration.ofHours(24);
    private static final Duration CHECK_48H = Duration.ofHours(48);

    /**
     * Dipanggil setelah screening cycle. Skip symbol yang sudah punya log
     * dalam DEDUP_WINDOW. Return HANYA candidate yang beneran baru di-log
     * (bukan yang di-skip karena dedup) — dipakai caller buat gating Telegram,
     * supaya notif gak nyampah tiap cycle buat coin yang sama.
     */
    @Transactional
    public List<CoinCandidate> logNewPicks(List<CoinCandidate> topCandidates) {
        Instant now = Instant.now();
        Instant dedupCutoff = now.minus(DEDUP_WINDOW);
        List<CoinCandidate> newlyLogged = new ArrayList<>();

        for (CoinCandidate c : topCandidates) {
            boolean alreadyLogged = !screenerPickLogRepository
                    .findRecentBySymbol(c.getSymbol(), dedupCutoff).isEmpty();
            if (alreadyLogged) continue;

            ScreenerPickLog logEntry = ScreenerPickLog.builder()
                    .symbol(c.getSymbol())
                    .pickedAt(now)
                    .entryPrice(c.getLastPrice())
                    .scoreAtPick(c.getScore())
                    .verdictAtPick(c.getVerdict())
                    .slAtPick(c.getSuggestedSl())
                    .tpAtPick(c.getSuggestedTp())
                    .rankAtPick(c.getRankPosition())
                    .checked24h(false)
                    .checked48h(false)
                    .build();

            screenerPickLogRepository.save(logEntry);
            newlyLogged.add(c);
            log.info("Screener validation: log baru untuk {} @ {} (verdict={})", c.getSymbol(), c.getLastPrice(), c.getVerdict());
        }
        return newlyLogged;
    }

    /**
     * Dipanggil scheduler terpisah. Cari log yang pickedAt sudah lewat 24 jam
     * dan belum di-cek. BEDA dari versi lama: sekarang cek PATH harga (candle
     * 1h di antara pickedAt sampai +24h), bukan cuma titik akhir — supaya
     * "TP sempat kesentuh lalu turun lagi" gak salah kecatat sebagai rugi.
     */
    public void checkPending24h() {
        Instant cutoff = Instant.now().minus(CHECK_24H);
        List<ScreenerPickLog> pending = screenerPickLogRepository.findByChecked24hFalseAndPickedAtBefore(cutoff);

        for (ScreenerPickLog logEntry : pending) {
            try {
                BigDecimal currentPrice = fetchCurrentPrice(logEntry.getSymbol());
                if (currentPrice == null) continue;

                List<double[]> highLowSeries = fetchHighLowSeries(
                        logEntry.getSymbol(), logEntry.getPickedAt(), logEntry.getPickedAt().plus(CHECK_24H));
                String outcome = determinePathOutcome(logEntry.getSlAtPick(), logEntry.getTpAtPick(), highLowSeries);

                logEntry.setPrice24h(currentPrice);
                logEntry.setChangePercent24h(calculateChangePercent(logEntry.getEntryPrice(), currentPrice));
                logEntry.setOutcome24h(outcome);
                logEntry.setChecked24h(true);
                screenerPickLogRepository.save(logEntry);

                log.info("Validation 24h: {} entry={} now={} outcome={}",
                        logEntry.getSymbol(), logEntry.getEntryPrice(), currentPrice, outcome);
            } catch (Exception e) {
                log.error("Gagal cek validation 24h untuk {}", logEntry.getSymbol(), e);
            }
        }
    }

    public void checkPending48h() {
        Instant cutoff = Instant.now().minus(CHECK_48H);
        List<ScreenerPickLog> pending = screenerPickLogRepository.findByChecked48hFalseAndPickedAtBefore(cutoff);

        for (ScreenerPickLog logEntry : pending) {
            try {
                BigDecimal currentPrice = fetchCurrentPrice(logEntry.getSymbol());
                if (currentPrice == null) continue;

                List<double[]> highLowSeries = fetchHighLowSeries(
                        logEntry.getSymbol(), logEntry.getPickedAt(), logEntry.getPickedAt().plus(CHECK_48H));
                String outcome = determinePathOutcome(logEntry.getSlAtPick(), logEntry.getTpAtPick(), highLowSeries);

                logEntry.setPrice48h(currentPrice);
                logEntry.setChangePercent48h(calculateChangePercent(logEntry.getEntryPrice(), currentPrice));
                logEntry.setOutcome48h(outcome);
                logEntry.setChecked48h(true);
                screenerPickLogRepository.save(logEntry);

                log.info("Validation 48h: {} entry={} now={} outcome={}",
                        logEntry.getSymbol(), logEntry.getEntryPrice(), currentPrice, outcome);
            } catch (Exception e) {
                log.error("Gagal cek validation 48h untuk {}", logEntry.getSymbol(), e);
            }
        }
    }

    private BigDecimal fetchCurrentPrice(String symbol) {
        BinancePriceDto response = binancePublicRestClient.get()
                .uri("/api/v3/ticker/price?symbol={symbol}", symbol)
                .retrieve()
                .body(BinancePriceDto.class);
        return response == null ? null : new BigDecimal(response.getPrice());
    }

    /**
     * Ambil high/low candle 1h di antara start-end. Dipakai buat cek SL/TP
     * mana yang kesentuh DULUAN secara kronologis — bukan sekadar bandingin
     * harga di titik akhir doang (itu bisa nyembunyiin fakta TP udah kesentuh
     * lalu turun lagi, atau SL udah kesentuh lalu balik naik).
     */
    private List<double[]> fetchHighLowSeries(String symbol, Instant start, Instant end) {
        List<List<Object>> raw = binancePublicRestClient.get()
                .uri("/api/v3/klines?symbol={symbol}&interval=1h&startTime={start}&endTime={end}&limit=1000",
                        symbol, start.toEpochMilli(), end.toEpochMilli())
                .retrieve()
                .body(new ParameterizedTypeReference<List<List<Object>>>() {});

        List<double[]> result = new ArrayList<>();
        if (raw == null) return result;
        for (List<Object> k : raw) {
            double high = Double.parseDouble(k.get(2).toString());
            double low = Double.parseDouble(k.get(3).toString());
            result.add(new double[]{high, low});
        }
        return result;
    }

    private String determinePathOutcome(BigDecimal sl, BigDecimal tp, List<double[]> highLowSeries) {
        // Pick lama (sebelum kolom sl_at_pick/tp_at_pick ada) bakal null — jangan crash.
        if (sl == null || tp == null) return "NEITHER";

        double slVal = sl.doubleValue();
        double tpVal = tp.doubleValue();

        for (double[] hl : highLowSeries) {
            double high = hl[0];
            double low = hl[1];
            // Asumsi konservatif: dalam 1 candle yang sama, kalau DUA-duanya
            // kesentuh (high>=TP dan low<=SL), kita anggap SL duluan — gak
            // bisa tau urutan intra-candle dari data OHLC biasa, dan asumsi
            // pesimis lebih aman daripada optimis buat validasi jujur.
            if (low <= slVal) return "SL_HIT";
            if (high >= tpVal) return "TP_HIT";
        }
        return "NEITHER";
    }

    private BigDecimal calculateChangePercent(BigDecimal entryPrice, BigDecimal currentPrice) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return currentPrice.subtract(entryPrice)
                .divide(entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }
}
package com.bot.testnet.crypto.service.screener;

import com.bot.testnet.crypto.model.dto.BinancePriceDto;
import com.bot.testnet.crypto.model.entity.CoinCandidate;
import com.bot.testnet.crypto.model.entity.ScreenerPickLog;
import com.bot.testnet.crypto.repository.ScreenerPickLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
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
     * Dipanggil setelah screening cycle, kirim top 3 (atau berapa pun mau
     * di-track). Skip symbol yang sudah punya log dalam DEDUP_WINDOW,
     * supaya coin yang tetap di top 3 selama berjam-jam tidak dobel-log
     * tiap cycle (screener jalan tiap jam).
     */
    @Transactional
    public void logNewPicks(List<CoinCandidate> topCandidates) {
        Instant now = Instant.now();
        Instant dedupCutoff = now.minus(DEDUP_WINDOW);

        for (CoinCandidate c : topCandidates) {
            boolean alreadyLogged = !screenerPickLogRepository
                    .findRecentBySymbol(c.getSymbol(), dedupCutoff).isEmpty();
            if (alreadyLogged) continue;

            ScreenerPickLog logEntry = ScreenerPickLog.builder()
                    .symbol(c.getSymbol())
                    .pickedAt(now)
                    .entryPrice(c.getLastPrice())
                    .scoreAtPick(c.getScore())
                    .rankAtPick(c.getRankPosition())
                    .checked24h(false)
                    .checked48h(false)
                    .build();

            screenerPickLogRepository.save(logEntry);
            log.info("Screener validation: log baru untuk {} @ {}", c.getSymbol(), c.getLastPrice());
        }
    }

    /**
     * Dipanggil scheduler terpisah. Cari log yang pickedAt sudah lewat 24 jam
     * dan belum di-cek, fetch harga sekarang, hitung perubahan %.
     */
    public void checkPending24h() {
        Instant cutoff = Instant.now().minus(CHECK_24H);
        List<ScreenerPickLog> pending = screenerPickLogRepository.findByChecked24hFalseAndPickedAtBefore(cutoff);

        for (ScreenerPickLog logEntry : pending) {
            try {
                BigDecimal currentPrice = fetchCurrentPrice(logEntry.getSymbol());
                if (currentPrice == null) continue;

                BigDecimal changePercent = calculateChangePercent(logEntry.getEntryPrice(), currentPrice);

                logEntry.setPrice24h(currentPrice);
                logEntry.setChangePercent24h(changePercent);
                logEntry.setChecked24h(true);
                screenerPickLogRepository.save(logEntry);

                log.info("Validation 24h: {} entry={} now={} change={}%",
                        logEntry.getSymbol(), logEntry.getEntryPrice(), currentPrice, changePercent);
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

                BigDecimal changePercent = calculateChangePercent(logEntry.getEntryPrice(), currentPrice);

                logEntry.setPrice48h(currentPrice);
                logEntry.setChangePercent48h(changePercent);
                logEntry.setChecked48h(true);
                screenerPickLogRepository.save(logEntry);

                log.info("Validation 48h: {} entry={} now={} change={}%",
                        logEntry.getSymbol(), logEntry.getEntryPrice(), currentPrice, changePercent);
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

    private BigDecimal calculateChangePercent(BigDecimal entryPrice, BigDecimal currentPrice) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return currentPrice.subtract(entryPrice)
                .divide(entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }
}
package com.bot.testnet.crypto.service.screener;

import com.bot.testnet.crypto.model.dto.BinanceTicker24hDto;
import com.bot.testnet.crypto.model.entity.CoinCandidate;
import com.bot.testnet.crypto.repository.CoinCandidateRepository;
import com.bot.testnet.crypto.service.TelegramNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class CoinScreenerService {

    private final CoinCandidateRepository coinCandidateRepository;

    @Qualifier("binancePublicRestClient")
    private final RestClient binancePublicRestClient;
    private final ScreenerValidationService screenerValidationService;
    private final ScreenerStrategyService screenerStrategyService;
    private final TelegramNotificationService telegramNotificationService;

    // Exclude berdasarkan BASE ASSET (bukan symbol pair penuh) — lebih robust
    // daripada daftar pair satu-satu, karena stablecoin baru (RLUSD, USDD, dst)
    // otomatis ketutup tanpa perlu update manual tiap ada listing baru.
    private static final Set<String> STABLECOIN_BASE_ASSETS = Set.of(
            "USDC", "FDUSD", "TUSD", "BUSD", "DAI", "RLUSD", "USDD", "PYUSD", "USDE", "USD1"
    );

    private static final Set<String> EXCLUDED_SYMBOLS = Set.of(
            "BTCUSDT", // benchmark relative-strength, skornya selalu 0 by construction — bukan kandidat
            "BNBUSDT", "ETHUSDT" // sudah jadi pair live trading kamu, percuma di-screen ulang
    );

    private static final BigDecimal MIN_QUOTE_VOLUME_24H = new BigDecimal("5000000"); // filter kasar: minimal $5jt volume 24h, buang coin illiquid
    private static final int TOP_N = 10;

    /**
     * Entry point dipanggil scheduler. Semua kerja berat (network call,
     * scoring) dilakukan DI LUAR transaksi DB — baru setelah List<CoinCandidate>
     * final baru masuk ke persistTopCandidates() yang transactional.
     */
    public void runScreeningCycle() {
        try {
            List<BinanceTicker24hDto> allTickers = fetchAllTickers();
            log.info("Screener: fetched {} tickers dari Binance", allTickers.size());

            BigDecimal btcChangePercent = extractBtcChangePercent(allTickers);
            if (btcChangePercent == null) {
                log.warn("Screener: BTCUSDT tidak ditemukan di response, skip cycle ini");
                return;
            }

            List<CoinCandidate> top10 = allTickers.stream()
                    .filter(this::passesLiquidityFilter)
                    .map(t -> toScoredCandidate(t, btcChangePercent))
                    .filter(c -> c != null)
                    .sorted(Comparator.comparing(CoinCandidate::getScore).reversed())
                    .limit(TOP_N)
                    .collect(Collectors.toList());

            for (int i = 0; i < top10.size(); i++) {
                top10.get(i).setRankPosition(i + 1);
            }

            // Dulu cuma top3 yang dievaluasi verdict — coin rank 4-10 permanen
            // gak pernah dapet second opinion teknikal. Sekarang semua top10
            // dievaluasi, cost REST call masih murah (10 klines call/jam).
            screenerStrategyService.enrichWithVerdict(top10);

            persistTopCandidates(top10);
            log.info("Screener: {} kandidat baru disimpan", top10.size());

            List<CoinCandidate> newlyLogged = screenerValidationService.logNewPicks(top10);

            List<CoinCandidate> newlyKuat = newlyLogged.stream()
                    .filter(c -> "KUAT".equals(c.getVerdict()))
                    .collect(Collectors.toList());
            if (!newlyKuat.isEmpty()) {
                sendTelegramAlert(newlyKuat); // 1 pesan aja, isi list semua yang KUAT (bisa lebih dari 3)
            }

        } catch (Exception e) {
            // Sengaja ditelan di level ini (bukan dibiarkan propagate ke scheduler)
            // supaya 1 cycle gagal (network error dsb) tidak bikin scheduler thread mati.
            log.error("Screener cycle gagal, akan dicoba lagi di cycle berikutnya", e);
        }
    }

    private List<BinanceTicker24hDto> fetchAllTickers() {
        BinanceTicker24hDto[] response = binancePublicRestClient.get()
                .uri("/api/v3/ticker/24hr")
                .retrieve()
                .body(BinanceTicker24hDto[].class);
        return response == null ? List.of() : List.of(response);
    }

    private BigDecimal extractBtcChangePercent(List<BinanceTicker24hDto> tickers) {
        return tickers.stream()
                .filter(t -> "BTCUSDT".equals(t.getSymbol()))
                .findFirst()
                .map(t -> safeParse(t.getPriceChangePercent()))
                .orElse(null);
    }

    private boolean passesLiquidityFilter(BinanceTicker24hDto t) {
        if (t.getSymbol() == null || !t.getSymbol().endsWith("USDT")) return false;
        if (EXCLUDED_SYMBOLS.contains(t.getSymbol())) return false;

        String baseAsset = t.getSymbol().substring(0, t.getSymbol().length() - 4); // strip "USDT"
        if (STABLECOIN_BASE_ASSETS.contains(baseAsset)) return false;

        BigDecimal quoteVolume = safeParse(t.getQuoteVolume());
        return quoteVolume != null && quoteVolume.compareTo(MIN_QUOTE_VOLUME_24H) >= 0;
    }

    /**
     * Scoring: kombinasi relative strength vs BTC + volume.
     * Asumsi eksplisit: bobot 60% relative strength, 40% volume ratio.
     * INI BELUM DIVALIDASI BACKTEST. Bobot ini tebakan awal, bukan hasil optimasi.
     */
    private CoinCandidate toScoredCandidate(BinanceTicker24hDto t, BigDecimal btcChangePercent) {
        BigDecimal priceChangePercent = safeParse(t.getPriceChangePercent());
        BigDecimal lastPrice = safeParse(t.getLastPrice());
        BigDecimal quoteVolume = safeParse(t.getQuoteVolume());

        if (priceChangePercent == null || lastPrice == null || quoteVolume == null) return null;

        BigDecimal relativeStrength = priceChangePercent.subtract(btcChangePercent);

        // Proxy volume spike: quoteVolume 24h dibanding threshold minimum.
        // CATATAN: ini BUKAN rolling average volume yang sesungguhnya (butuh
        // data historis candle yang tidak tersedia dari endpoint ticker/24hr
        // saja). Kalau mau volume spike yang akurat, perlu tarik candle
        // historis per-symbol — itu keluar dari desain "1 bulk call" ini.
        BigDecimal volumeRatio = quoteVolume.divide(MIN_QUOTE_VOLUME_24H, 4, RoundingMode.HALF_UP);

        BigDecimal score = relativeStrength.multiply(new BigDecimal("0.6"))
                .add(volumeRatio.multiply(new BigDecimal("0.4")));

        return CoinCandidate.builder()
                .symbol(t.getSymbol())
                .score(score)
                .priceChangePercent4h(priceChangePercent) // catatan: ini 24h dari Binance, bukan 4h — lihat README di bawah
                .relativeStrengthVsBtc(relativeStrength)
                .volumeSpikeRatio(volumeRatio)
                .lastPrice(lastPrice)
                .quoteVolume24h(quoteVolume)
                .generatedAt(Instant.now())
                .build();
    }

    /**
     * Cuma dipanggil buat pick BARU (bukan dedup-skip) dengan verdict KUAT.
     * Gak nyampah tiap cycle buat coin yang sama terus-terusan muncul.
     */
    private void sendTelegramAlert(List<CoinCandidate> newlyKuat) {
        try {
            StringBuilder message = new StringBuilder();
            for (CoinCandidate c : newlyKuat) {
                message.append(String.format(
                        "🟢 <b>%s</b>  |  Skor %s  |  Entry $%s  |  SL $%s  |  TP $%s\n",
                        c.getSymbol(),
                        c.getScore().setScale(1, RoundingMode.HALF_UP),
                        c.getSuggestedEntry() != null ? c.getSuggestedEntry().toPlainString() : "-",
                        c.getSuggestedSl() != null ? c.getSuggestedSl().toPlainString() : "-",
                        c.getSuggestedTp() != null ? c.getSuggestedTp().toPlainString() : "-"
                ));
            }
            telegramNotificationService.sendMessage("🔍 Coin Screener — Potensial KUAT", message.toString());
        } catch (Exception e) {
            // Gagal kirim Telegram TIDAK BOLEH gagalkan cycle screening — data tetap tersimpan.
            log.error("Gagal kirim notif Telegram untuk pick KUAT", e);
        }
    }

    private BigDecimal safeParse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Transaksi murni DB, tidak ada network call di dalamnya.
     * Delete + insert dalam satu transaksi supaya atomic: kalau gagal
     * di tengah, rollback, tabel lama tetap utuh (tidak pernah kosong).
     */
    @Transactional
    public void persistTopCandidates(List<CoinCandidate> newTop10) {
        coinCandidateRepository.deleteAll();
        coinCandidateRepository.saveAll(newTop10);
    }
}
package com.bot.testnet.crypto.service.screener;

import com.bot.testnet.crypto.model.dto.BinancePriceDto;
import com.bot.testnet.crypto.model.entity.CoinCandidate;
import com.bot.testnet.crypto.repository.CoinCandidateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetch harga LIVE (bukan snapshot) untuk symbol yang lagi tersimpan sebagai
 * kandidat. Dipanggil terpisah dari CoinScreenerService — tidak ikut hitung
 * ulang skor/verdict, cuma ambil harga terkini buat bandingin sama snapshot
 * yang ditampilkan dashboard (deteksi data basi).
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class ScreenerLivePriceService {

    @Qualifier("binancePublicRestClient")
    private final RestClient binancePublicRestClient;

    private final CoinCandidateRepository coinCandidateRepository;

    public Map<String, BigDecimal> fetchLivePricesForCurrentCandidates() {
        List<CoinCandidate> candidates = coinCandidateRepository.findAllOrderedByRank();
        Map<String, BigDecimal> result = new LinkedHashMap<>();

        for (CoinCandidate c : candidates) {
            try {
                BigDecimal price = fetchPrice(c.getSymbol());
                if (price != null) result.put(c.getSymbol(), price);
            } catch (Exception e) {
                // Skip symbol yang gagal fetch, jangan gagalkan seluruh response
                // cuma karena 1 symbol error (network blip, symbol delisting, dll).
                log.warn("Gagal fetch live price untuk {}", c.getSymbol());
            }
        }
        return result;
    }

    private BigDecimal fetchPrice(String symbol) {
        BinancePriceDto response = binancePublicRestClient.get()
                .uri("/api/v3/ticker/price?symbol={symbol}", symbol)
                .retrieve()
                .body(BinancePriceDto.class);
        return response == null ? null : new BigDecimal(response.getPrice());
    }
}
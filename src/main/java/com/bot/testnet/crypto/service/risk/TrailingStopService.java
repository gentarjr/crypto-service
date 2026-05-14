package com.bot.testnet.crypto.service.risk;

import com.bot.testnet.crypto.model.dto.StrategyType;
import com.bot.testnet.crypto.model.dto.VirtualPosition;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * ATR-based Trailing Stop Loss Service
 *
 * Hanya aktif untuk EMA_CROSSOVER strategy (trending)
 * BB_MEAN_REVERSION tetap pakai fixed TP
 */
@Service
@Log4j2
public class TrailingStopService {

    @Value("${trading.risk.trailing-atr-multiplier:1.5}")
    private double trailingAtrMultiplier;

    /**
     * Update trailing SL untuk open position
     * Dipanggil tiap candle close
     *
     * @param position        open position yang akan di-update
     * @param currentPrice    harga close candle terbaru
     * @param snapshot        indicator snapshot (untuk dapat ATR terbaru)
     * @return true kalau SL berhasil di-update (naik)
     */
    public boolean update(VirtualPosition position,
                          BigDecimal currentPrice,
                          GetIndicatorResponse snapshot) {

        // Hanya untuk EMA strategy (trending)
        if (position.getStrategy() != StrategyType.EMA_CROSSOVER) {
            log.debug("Trailing SL: skip (strategy={})", position.getStrategy());
            return false;
        }

        // Update highest price
        position.updateHighestPrice(currentPrice);

        // Cek apakah trailing sudah aktif
        if (!position.isTrailingActive()) {
            // Cek activation condition: profit >= 1R
            if (position.isBreakevenActivationReached(currentPrice)) {
                position.setTrailingActive(true);

                // Pertama: pindahkan SL ke breakeven (entry price)
                boolean updated = position.ratchetStopLoss(position.getEntryPrice());
                if (updated) {
                    log.info("🔓 Trailing SL ACTIVATED for #{}", position.getId());
                    log.info("   Profit reached 1R → SL moved to breakeven: ${}",
                            position.getEntryPrice());
                }
                return updated;
            }

            // Belum mencapai 1R, log unrealized profit
            BigDecimal unrealized = position.calculateUnrealizedPnl(currentPrice);
            BigDecimal oneR = position.getEntryPrice()
                    .subtract(position.getInitialStopLoss()).abs();
            log.debug("⏳ Trailing not yet active. Unrealized: ${} / 1R: ${}",
                    String.format("%.4f", unrealized.doubleValue()),
                    String.format("%.4f", oneR.doubleValue()));
            return false;
        }

        // Trailing sudah aktif — update dengan ATR
        BigDecimal atr = snapshot.getAtr();
        BigDecimal newTrailingSL = position.calculateTrailingSL(atr, trailingAtrMultiplier);

        boolean updated = position.ratchetStopLoss(newTrailingSL);

        if (updated) {
            log.info("📈 Trailing SL updated for #{}: ${} → ${}",
                    position.getId(),
                    String.format("%.4f", position.getStopLoss()
                            .subtract(newTrailingSL.subtract(position.getStopLoss())).doubleValue()),
                    String.format("%.4f", position.getStopLoss().doubleValue()));
            log.info("   Highest: ${} | ATR: ${} | Trail dist: ${}",
                    String.format("%.4f", position.getHighestPrice().doubleValue()),
                    String.format("%.4f", atr.doubleValue()),
                    String.format("%.4f", atr.multiply(
                            BigDecimal.valueOf(trailingAtrMultiplier)).doubleValue()));
        }

        return updated;
    }
}
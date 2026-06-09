package com.bot.testnet.crypto.service.risk;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Shared trailing SL logic untuk Paper dan Live trading
 *
 * Menggantikan TrailingStopService yang sebelumnya hanya untuk Paper
 */
@Component
@Log4j2
@RequiredArgsConstructor
public class TrailingStopHelper {

    @Value("${trading.risk.trailing-atr-multiplier:1.5}")
    private double trailingAtrMultiplier;

    /**
     * Generic trailing SL update
     * Bekerja untuk VirtualPosition dan LivePosition
     * karena sama-sama punya method yang dibutuhkan
     */
    public <T extends TrailablePosition> boolean update(
            T position, BigDecimal currentPrice, BigDecimal atr, String label) {

        position.updateHighestPrice(currentPrice);

        if (!position.isTrailingActive()) {
            BigDecimal oneR = position.getEntryPrice()
                    .subtract(position.getInitialStopLoss()).abs();
            BigDecimal halfRTrigger = position.getEntryPrice()
                    .add(oneR.multiply(new BigDecimal("0.5")));

            if (currentPrice.compareTo(halfRTrigger) >= 0) {
                BigDecimal breakevenSL = position.getEntryPrice()
                        .multiply(new BigDecimal("1.00075")); // cover buy fee
                boolean updated = position.ratchetStopLoss(breakevenSL);
                if (updated) {
                    log.info("🔒 [{}] Breakeven SL at 0.5R → ${}", label, breakevenSL);
                }
            }
        }

        if (!position.isTrailingActive()
                && position.isBreakevenActivationReached(currentPrice)) {
            position.setTrailingActive(true);
            BigDecimal feeBuffer = position.getEntryPrice()
                    .multiply(new BigDecimal("0.002"));
            BigDecimal breakevenSL = position.getEntryPrice().add(feeBuffer);
            boolean updated = position.ratchetStopLoss(breakevenSL);
            if (updated) {
                log.info("🔓 [{}] Trailing activated → breakeven+fee ${}",
                        label, breakevenSL);
            }
            return updated;
        }

        if (position.isTrailingActive()) {
            BigDecimal newSL = position.calculateTrailingSL(atr, trailingAtrMultiplier);
            boolean updated = position.ratchetStopLoss(newSL);
            if (updated) {
                log.info("📈 [{}] Trailing SL → ${}", label, position.getStopLoss());
            }
            return updated;
        }

        return false;
    }
}
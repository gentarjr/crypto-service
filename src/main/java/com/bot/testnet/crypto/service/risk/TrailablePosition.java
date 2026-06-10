package com.bot.testnet.crypto.service.risk;

import com.bot.testnet.crypto.model.dto.StrategyType;

import java.math.BigDecimal;

/**
 * Interface untuk posisi yang support trailing SL
 * Implemented oleh VirtualPosition dan LivePosition
 */
public interface TrailablePosition {
    BigDecimal getEntryPrice();
    BigDecimal getStopLoss();
    boolean isTrailingActive();
    void setTrailingActive(boolean active);
    boolean isBreakevenActivationReached(BigDecimal price);
    void updateHighestPrice(BigDecimal price);
    boolean ratchetStopLoss(BigDecimal newSL);
    BigDecimal calculateTrailingSL(BigDecimal atr, double multiplier);
    BigDecimal getInitialStopLoss();
    StrategyType getStrategy();
}
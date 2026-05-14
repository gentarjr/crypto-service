package com.bot.testnet.crypto.service.exchange;

import com.bot.testnet.crypto.model.dto.Signal;
import com.bot.testnet.crypto.model.dto.SignalAction;
import com.bot.testnet.crypto.model.dto.StrategyType;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Master controller untuk adaptive strategy.
 *
 * Pilih strategi berdasarkan ADX regime:
 * - TRENDING / STRONG_TRENDING → EMA Crossover
 * - RANGING                    → BB Mean Reversion
 * - TRANSITION                 → NO TRADE
 *
 * Plus: signal deduplication untuk hindari notif spam
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class AdaptiveSignalService {

    private final EmaSignalService emaSignalService;
    private final BbSignalService bbSignalService;

    /**
     * Track signal terakhir untuk deduplication
     * In-memory only (reset saat restart)
     */
    private SignalAction lastSignalAction = SignalAction.HOLD;
    private StrategyType lastStrategyType = null;

    /**
     * Evaluate indicator snapshot dan return signal
     * dengan regime-based strategy selection
     */
    public Signal evaluate(GetIndicatorResponse snapshot) {
        String regime = snapshot.getMarketRegime();

        log.info("📋 Regime: {} → evaluating strategy...", regime);

        Signal signal = switch (regime) {
            case "TRENDING", "STRONG_TRENDING" -> {
                log.info("📈 Trending market → EMA Crossover strategy");
                yield emaSignalService.evaluate(snapshot);
            }
            case "RANGING" -> {
                log.info("〰️ Ranging market → BB Mean Reversion strategy");
                yield bbSignalService.evaluate(snapshot);
            }
            default -> {
                // TRANSITION zone (ADX 20-25)
                log.info("⏸️ Transition zone (ADX 20-25) → NO TRADE");
                yield Signal.noTrade("ADX in transition zone — waiting for clear regime");
            }
        };

        return signal;
    }

    /**
     * Cek apakah signal ini "baru" (berbeda dari sebelumnya)
     * untuk hindari kirim notif yang sama berulang
     *
     * @return true kalau signal BARU dan perlu notif
     */
    public boolean isNewActionableSignal(Signal signal) {
        // Hanya peduli dengan BUY/SELL (HOLD tidak perlu notif)
        if (!signal.isActionable()) {
            // Update last state ke HOLD kalau sebelumnya ada signal
            if (lastSignalAction != SignalAction.HOLD) {
                log.debug("Signal changed: {} → HOLD", lastSignalAction);
                lastSignalAction = SignalAction.HOLD;
                lastStrategyType = null;
            }
            return false;
        }

        // BUY/SELL signal — cek apakah sama dengan sebelumnya
        boolean isSameSignal = lastSignalAction == signal.getAction()
                && lastStrategyType == signal.getStrategy();

        if (isSameSignal) {
            log.info("🔄 Signal same as last ({} {}), skip notification",
                    signal.getAction(), signal.getStrategy());
            return false;
        }

        // Signal berbeda → ini signal BARU
        log.info("✨ NEW signal: {} → {} {}",
                lastSignalAction, signal.getAction(), signal.getStrategy());

        // Update state
        lastSignalAction = signal.getAction();
        lastStrategyType = signal.getStrategy();

        return true;
    }
}
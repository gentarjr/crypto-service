package com.bot.testnet.crypto.service.exchange;

import com.bot.testnet.crypto.model.dto.Signal;
import com.bot.testnet.crypto.model.dto.SignalAction;
import com.bot.testnet.crypto.model.dto.StrategyType;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "trading.pair-eth.enabled", havingValue = "true")
@RequiredArgsConstructor
@Log4j2
public class AdaptiveSignalServiceEth {

    private final EmaSignalServiceEth emaSignalServiceEth;
    private final BbSignalServiceEth bbSignalServiceEth;

    private SignalAction lastSignalAction = SignalAction.HOLD;
    private StrategyType lastStrategyType = null;

    public Signal evaluate(GetIndicatorResponse snapshot) {
        String regime = snapshot.getMarketRegime();

        log.info("📋 [ETH] Regime: {} → evaluating strategy...", regime);

        Signal signal = switch (regime) {
            case "TRENDING", "STRONG_TRENDING" -> {
                log.info("📈 [ETH] Trending market → EMA Crossover strategy");
                yield emaSignalServiceEth.evaluate(snapshot);
            }
            case "RANGING" -> {
                log.info("〰️ [ETH] Ranging market → BB Mean Reversion strategy");
                yield bbSignalServiceEth.evaluate(snapshot);
            }
            default -> {
                log.info("⏸️ [ETH] Transition zone (ADX 20-25) → NO TRADE");
                yield Signal.noTrade("ADX in transition zone — waiting for clear regime");
            }
        };

        return signal;
    }

    public boolean isNewActionableSignal(Signal signal) {
        if (!signal.isActionable()) {
            if (lastSignalAction != SignalAction.HOLD) {
                log.debug("[ETH] Signal changed: {} → HOLD", lastSignalAction);
                lastSignalAction = SignalAction.HOLD;
                lastStrategyType = null;
            }
            return false;
        }

        boolean isSameSignal = lastSignalAction == signal.getAction()
                && lastStrategyType == signal.getStrategy();

        if (isSameSignal) {
            log.info("🔄 [ETH] Signal same as last ({} {}), skip notification",
                    signal.getAction(), signal.getStrategy());
            return false;
        }

        log.info("✨ [ETH] NEW signal: {} → {} {}",
                lastSignalAction, signal.getAction(), signal.getStrategy());

        lastSignalAction = signal.getAction();
        lastStrategyType = signal.getStrategy();

        return true;
    }
}
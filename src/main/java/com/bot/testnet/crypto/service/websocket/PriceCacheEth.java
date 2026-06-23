package com.bot.testnet.crypto.service.websocket;

import com.bot.testnet.crypto.model.dto.PriceTick;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;


@Component
@ConditionalOnProperty(name = "trading.pair-eth.enabled", havingValue = "true")
@Log4j2
public class PriceCacheEth {

    private final AtomicReference<PriceTick> latestTick = new AtomicReference<>();
    private final AtomicReference<Instant> lastUpdateTime = new AtomicReference<>();

    private final List<Consumer<PriceTick>> observers = new CopyOnWriteArrayList<>();

    public void addObserver(Consumer<PriceTick> observer) {
        observers.add(observer);
        log.info("📡 [ETH] Price observer registered. Total: {}", observers.size());
    }

    public void updatePrice(PriceTick tick) {
        latestTick.set(tick);
        lastUpdateTime.set(Instant.now());
        log.debug("💹 [ETH] Price: {} = {}", tick.getSymbol(), tick.getPrice());

        observers.forEach(observer -> {
            try {
                observer.accept(tick);
            } catch (Exception e) {
                log.error("❌ [ETH] Error in price observer: {}", e.getMessage());
            }
        });
    }

    public BigDecimal getLatestPrice() {
        PriceTick tick = latestTick.get();
        return tick != null ? tick.getPrice() : null;
    }

    public PriceTick getLatestTick() {
        return latestTick.get();
    }

    public boolean isFresh() {
        Instant lastUpdate = lastUpdateTime.get();
        if (lastUpdate == null) return false;
        return Instant.now().minusSeconds(30).isBefore(lastUpdate);
    }

    public Instant getLastUpdateTime() {
        return lastUpdateTime.get();
    }
}
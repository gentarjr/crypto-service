package com.bot.testnet.crypto.service.websocket;

import com.bot.testnet.crypto.model.dto.PriceTick;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Thread-safe cache untuk latest price dari WebSocket
 *
 * Tambahan: Observer support untuk notify listeners saat price update
 */
@Component
@Log4j2
public class PriceCache {

    private final AtomicReference<PriceTick> latestTick = new AtomicReference<>();
    private final AtomicReference<Instant> lastUpdateTime = new AtomicReference<>();

    // Observer list (thread-safe)
    private final List<Consumer<PriceTick>> observers = new CopyOnWriteArrayList<>();

    /**
     * Register observer untuk menerima price tick
     */
    public void addObserver(Consumer<PriceTick> observer) {
        observers.add(observer);
        log.info("📡 Price observer registered. Total: {}", observers.size());
    }

    /**
     * Update price dari WebSocket tick + notify observers
     */
    public void updatePrice(PriceTick tick) {
        latestTick.set(tick);
        lastUpdateTime.set(Instant.now());
        log.debug("💹 Price: {} = {}", tick.getSymbol(), tick.getPrice());

        // Notify all observers
        observers.forEach(observer -> {
            try {
                observer.accept(tick);
            } catch (Exception e) {
                log.error("❌ Error in price observer: {}", e.getMessage());
                // Tidak throw — satu observer yang error tidak boleh stop yang lain
            }
        });
    }

    /**
     * Get latest price
     */
    public BigDecimal getLatestPrice() {
        PriceTick tick = latestTick.get();
        return tick != null ? tick.getPrice() : null;
    }

    /**
     * Get latest tick
     */
    public PriceTick getLatestTick() {
        return latestTick.get();
    }

    /**
     * Apakah price data fresh? (dalam 30 detik)
     */
    public boolean isFresh() {
        Instant lastUpdate = lastUpdateTime.get();
        if (lastUpdate == null) return false;
        return Instant.now().minusSeconds(30).isBefore(lastUpdate);
    }

    /**
     * Kapan terakhir update
     */
    public Instant getLastUpdateTime() {
        return lastUpdateTime.get();
    }
}
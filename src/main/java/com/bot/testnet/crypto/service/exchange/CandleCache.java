package com.bot.testnet.crypto.service.exchange;  // sesuaikan package Anda

import com.bot.testnet.crypto.model.dto.Candle;
import com.bot.testnet.crypto.model.dto.CandleUpdateResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe in-memory cache untuk candle data.
 *
 * Menyimpan state explicit untuk last candle:
 * - lastCandleWasLive: apakah candle terakhir di cache statusnya live saat di-cache
 *
 * Ini penting untuk detect transition live → closed
 */
@Component
@Slf4j
public class CandleCache {

    private static final int MAX_CACHE_SIZE = 500;

    private final List<Candle> candles = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Track state: apakah candle terakhir di cache masih live saat ditambahkan/di-update?
     * Diperlukan untuk detect transition LIVE → CLOSED
     */
    private boolean lastCandleWasLive = false;

    /**
     * Initialize cache dengan candle awal (dari fetch saat startup)
     */
    public void initialize(List<Candle> initialCandles) {
        lock.writeLock().lock();
        try {
            candles.clear();
            candles.addAll(initialCandles);
            trimToMaxSize();

            // Update state berdasarkan candle terakhir
            updateLastCandleLiveState();

            log.info("📦 Cache initialized with {} candles (last candle live: {})",
                    candles.size(), lastCandleWasLive);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Tambah atau update candle, return enum result yang descriptive
     */
    public CandleUpdateResult addOrUpdateCandle(Candle newCandle) {
        lock.writeLock().lock();
        try {
            Instant now = Instant.now();
            boolean newCandleIsClosed = isCandleClosed(newCandle, now);

            // ─── Case 1: Cache kosong ───
            if (candles.isEmpty()) {
                candles.add(newCandle);
                lastCandleWasLive = !newCandleIsClosed;

                log.info("📊 First candle added: {} @ close {}",
                        newCandle.getCloseTime(), newCandle.getClose());

                return newCandleIsClosed
                        ? CandleUpdateResult.NEW_CLOSED
                        : CandleUpdateResult.NEW_LIVE;
            }

            Candle lastCandle = candles.get(candles.size() - 1);

            // ─── Case 2: Candle BARU (lebih baru dari last cached) ───
            if (newCandle.getOpenTime().isAfter(lastCandle.getOpenTime())) {
                candles.add(newCandle);
                trimToMaxSize();
                lastCandleWasLive = !newCandleIsClosed;

                if (newCandleIsClosed) {
                    log.info("📊 NEW closed candle added: {} @ close {}",
                            newCandle.getCloseTime(), newCandle.getClose());
                    return CandleUpdateResult.NEW_CLOSED;
                } else {
                    log.info("📊 NEW live candle added: {} @ close {}",
                            newCandle.getCloseTime(), newCandle.getClose());
                    return CandleUpdateResult.NEW_LIVE;
                }
            }

            // ─── Case 3: Sama openTime → UPDATE existing candle ───
            if (newCandle.getOpenTime().equals(lastCandle.getOpenTime())) {
                // ⭐ KEY LOGIC: cek state SEBELUM update
                boolean wasLiveBefore = this.lastCandleWasLive;

                // Replace candle
                candles.set(candles.size() - 1, newCandle);

                // Update tracked state
                lastCandleWasLive = !newCandleIsClosed;

                // Detect transition: live → closed
                if (wasLiveBefore && newCandleIsClosed) {
                    log.info("🎯 Live candle TRANSITIONED to CLOSED: {} @ close {}",
                            newCandle.getCloseTime(), newCandle.getClose());
                    return CandleUpdateResult.UPDATED_NOW_CLOSED;
                }

                // Normal update (still live)
                log.debug("🔄 Updated live candle: close={}", newCandle.getClose());
                return CandleUpdateResult.UPDATED_STILL_LIVE;
            }

            // ─── Case 4: Candle lebih lama dari last (shouldn't happen) ───
            log.error("⚠️ Unexpected older candle (scheduler filter failed?): {} (last: {})",
                    newCandle.getOpenTime(), lastCandle.getOpenTime());
            return CandleUpdateResult.OLDER_IGNORED;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get all candles (read-only copy)
     */
    public List<Candle> getAllCandles() {
        lock.readLock().lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(candles));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get last N candles
     */
    public List<Candle> getLastNCandles(int n) {
        lock.readLock().lock();
        try {
            int size = candles.size();
            if (size <= n) {
                return Collections.unmodifiableList(new ArrayList<>(candles));
            }
            return Collections.unmodifiableList(
                    new ArrayList<>(candles.subList(size - n, size))
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get last candle (could be live or closed)
     */
    public Candle getLastCandle() {
        lock.readLock().lock();
        try {
            return candles.isEmpty() ? null : candles.get(candles.size() - 1);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get last CLOSED candle (skip live candle)
     */
    public Candle getLastClosedCandle() {
        lock.readLock().lock();
        try {
            Instant now = Instant.now();
            for (int i = candles.size() - 1; i >= 0; i--) {
                Candle c = candles.get(i);
                if (isCandleClosed(c, now)) {
                    return c;
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Cache size
     */
    public int size() {
        lock.readLock().lock();
        try {
            return candles.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Cek apakah last candle masih dalam state live (saat ini di cache)
     */
    public boolean isLastCandleLive() {
        lock.readLock().lock();
        try {
            return lastCandleWasLive;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ═══════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════

    /**
     * Helper: cek apakah candle sudah closed relative to reference time
     */
    private boolean isCandleClosed(Candle candle, Instant referenceTime) {
        return candle.getCloseTime().isBefore(referenceTime);
    }

    /**
     * Update tracked state berdasarkan candle terakhir di list
     */
    private void updateLastCandleLiveState() {
        if (candles.isEmpty()) {
            lastCandleWasLive = false;
            return;
        }
        Candle lastCandle = candles.get(candles.size() - 1);
        lastCandleWasLive = !isCandleClosed(lastCandle, Instant.now());
    }

    /**
     * Trim cache supaya tidak melebihi MAX_CACHE_SIZE
     */
    private void trimToMaxSize() {
        while (candles.size() > MAX_CACHE_SIZE) {
            candles.remove(0);
        }
    }

    public List<Candle> getLastNClosedCandles(int n) {
        lock.readLock().lock();
        try {
            Instant now = Instant.now();
            List<Candle> closed = new ArrayList<>();
            for (int i = candles.size() - 1; i >= 0 && closed.size() < n; i--) {
                Candle c = candles.get(i);
                if (isCandleClosed(c, now)) {
                    closed.add(0, c); // prepend → urutan kronologis (lama → baru)
                }
            }
            return Collections.unmodifiableList(closed);
        } finally {
            lock.readLock().unlock();
        }
    }
}
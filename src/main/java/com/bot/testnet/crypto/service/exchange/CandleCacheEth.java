package com.bot.testnet.crypto.service.exchange;

import com.bot.testnet.crypto.model.dto.Candle;
import com.bot.testnet.crypto.model.dto.CandleUpdateResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;


@Component
@ConditionalOnProperty(name = "trading.pair-eth.enabled", havingValue = "true")
@Slf4j
public class CandleCacheEth {

    private static final int MAX_CACHE_SIZE = 500;

    private final List<Candle> candles = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private boolean lastCandleWasLive = false;

    public void initialize(List<Candle> initialCandles) {
        lock.writeLock().lock();
        try {
            candles.clear();
            candles.addAll(initialCandles);
            trimToMaxSize();
            updateLastCandleLiveState();
            log.info("📦 [ETH] Cache initialized with {} candles (last candle live: {})",
                    candles.size(), lastCandleWasLive);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CandleUpdateResult addOrUpdateCandle(Candle newCandle) {
        lock.writeLock().lock();
        try {
            Instant now = Instant.now();
            boolean newCandleIsClosed = isCandleClosed(newCandle, now);

            if (candles.isEmpty()) {
                candles.add(newCandle);
                lastCandleWasLive = !newCandleIsClosed;
                log.info("📊 [ETH] First candle added: {} @ close {}",
                        newCandle.getCloseTime(), newCandle.getClose());
                return newCandleIsClosed
                        ? CandleUpdateResult.NEW_CLOSED
                        : CandleUpdateResult.NEW_LIVE;
            }

            Candle lastCandle = candles.get(candles.size() - 1);

            if (newCandle.getOpenTime().isAfter(lastCandle.getOpenTime())) {
                candles.add(newCandle);
                trimToMaxSize();
                lastCandleWasLive = !newCandleIsClosed;

                if (newCandleIsClosed) {
                    log.info("📊 [ETH] NEW closed candle added: {} @ close {}",
                            newCandle.getCloseTime(), newCandle.getClose());
                    return CandleUpdateResult.NEW_CLOSED;
                } else {
                    log.info("📊 [ETH] NEW live candle added: {} @ close {}",
                            newCandle.getCloseTime(), newCandle.getClose());
                    return CandleUpdateResult.NEW_LIVE;
                }
            }

            if (newCandle.getOpenTime().equals(lastCandle.getOpenTime())) {
                boolean wasLiveBefore = this.lastCandleWasLive;
                candles.set(candles.size() - 1, newCandle);
                lastCandleWasLive = !newCandleIsClosed;

                if (wasLiveBefore && newCandleIsClosed) {
                    log.info("🎯 [ETH] Live candle TRANSITIONED to CLOSED: {} @ close {}",
                            newCandle.getCloseTime(), newCandle.getClose());
                    return CandleUpdateResult.UPDATED_NOW_CLOSED;
                }

                log.debug("🔄 [ETH] Updated live candle: close={}", newCandle.getClose());
                return CandleUpdateResult.UPDATED_STILL_LIVE;
            }

            log.error("⚠️ [ETH] Unexpected older candle (scheduler filter failed?): {} (last: {})",
                    newCandle.getOpenTime(), lastCandle.getOpenTime());
            return CandleUpdateResult.OLDER_IGNORED;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Candle> getAllCandles() {
        lock.readLock().lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(candles));
        } finally {
            lock.readLock().unlock();
        }
    }

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

    public Candle getLastCandle() {
        lock.readLock().lock();
        try {
            return candles.isEmpty() ? null : candles.get(candles.size() - 1);
        } finally {
            lock.readLock().unlock();
        }
    }

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

    public int size() {
        lock.readLock().lock();
        try {
            return candles.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isLastCandleLive() {
        lock.readLock().lock();
        try {
            return lastCandleWasLive;
        } finally {
            lock.readLock().unlock();
        }
    }

    private boolean isCandleClosed(Candle candle, Instant referenceTime) {
        return candle.getCloseTime().isBefore(referenceTime);
    }

    private void updateLastCandleLiveState() {
        if (candles.isEmpty()) {
            lastCandleWasLive = false;
            return;
        }
        Candle lastCandle = candles.get(candles.size() - 1);
        lastCandleWasLive = !isCandleClosed(lastCandle, Instant.now());
    }

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
                    closed.add(0, c);
                }
            }
            return Collections.unmodifiableList(closed);
        } finally {
            lock.readLock().unlock();
        }
    }
}
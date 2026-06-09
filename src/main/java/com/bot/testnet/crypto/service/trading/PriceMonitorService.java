package com.bot.testnet.crypto.service.trading;

import com.bot.testnet.crypto.model.dto.LivePosition;
import com.bot.testnet.crypto.model.dto.PriceTick;
import com.bot.testnet.crypto.model.dto.VirtualPosition;
import com.bot.testnet.crypto.service.websocket.PriceCache;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real-time SL/TP monitor menggunakan WebSocket price stream
 *
 * Throttle: cek SL/TP maksimal 1x per detik
 * Observer: register ke PriceCache untuk terima tiap tick
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class PriceMonitorService {

    private final PriceCache priceCache;
    private final PaperTradingService paperTradingService;
    private final OrderExecutorService orderExecutorService;

    // Throttle: track kapan terakhir check SL/TP
    private final AtomicLong lastCheckMs = new AtomicLong(0);
    private static final long CHECK_INTERVAL_MS = 1_000; // 1 detik

    /**
     * Register sebagai observer ke PriceCache
     * Dipanggil saat bean ready
     */
    @PostConstruct
    public void init() {
        priceCache.addObserver(this::onPriceTick);
        log.info("👁️  PriceMonitorService registered as price observer");
    }

    /**
     * Dipanggil tiap price tick dari WebSocket
     * Throttled: actual check cuma 1x per detik
     */
    private void onPriceTick(PriceTick tick) {
        long now = System.currentTimeMillis();
        long last = lastCheckMs.get();

        // Throttle check
        if (now - last < CHECK_INTERVAL_MS) {
            return; // Skip, belum waktunya check
        }

        // Update last check time (atomic compare-and-set)
        if (!lastCheckMs.compareAndSet(last, now)) {
            return; // Another thread sudah update, skip
        }

        // Lakukan actual check
        checkPositionSLTP(tick.getPrice());

        checkLivePosition(tick.getPrice());
    }

    /**
     * Check apakah open position hit SL atau TP
     */
    private void checkPositionSLTP(BigDecimal currentPrice) {
        VirtualPosition position = paperTradingService.getOpenPosition();

        if (position == null) {
            return; // Tidak ada open position, skip
        }

        // Check Take Profit
        if (position.isHitTakeProfit(currentPrice)) {
            log.info("🎯 TP HIT! Price {} >= TP {}",
                    currentPrice, position.getTakeProfit());
            paperTradingService.closePositionRealTime(currentPrice, "TAKE_PROFIT");
            return;
        }

        // Check Stop Loss
        if (position.isHitStopLoss(currentPrice)) {
            log.warn("🛑 SL HIT! Price {} <= SL {}",
                    currentPrice, position.getStopLoss());
            String reason = position.isTrailingActive() ? "TRAILING_STOP" : "STOP_LOSS";
            paperTradingService.closePositionRealTime(currentPrice, reason);
        }
    }

    private void checkLivePosition(BigDecimal price) {
        if (!orderExecutorService.isEnabled()) return;

        LivePosition position = orderExecutorService.getOpenPosition();
        if (position == null) return;

        // ✅ Update trailing SL dulu sebelum cek hit
        orderExecutorService.updateTrailingFromWebSocket(price);

        if (position.isHitTakeProfit(price)) {
            log.info("🎯 [LIVE WebSocket] TP HIT: {} >= {}",
                    price, position.getTakeProfit());
            orderExecutorService.closePositionFromWebSocket(price, "TAKE_PROFIT");
            return;
        }

        if (position.isHitStopLoss(price)) {
            String reason = position.isTrailingActive()
                    ? "TRAILING_STOP" : "STOP_LOSS";
            log.warn("🛑 [LIVE WebSocket] {} HIT: {} <= {}",
                    reason, price, position.getStopLoss());
            orderExecutorService.closePositionFromWebSocket(price, reason);
        }
    }
}
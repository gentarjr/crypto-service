package com.bot.testnet.crypto.service.trading;

import com.bot.testnet.crypto.model.dto.LivePosition;
import com.bot.testnet.crypto.model.dto.PriceTick;
import com.bot.testnet.crypto.service.websocket.PriceCacheEth;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Twin dari PriceMonitorService, khusus pair ETH/USDC.
 *
 * ⚠️ Tanpa class ini, BinanceWebSocketServiceEth tetap connect & nulis tick
 * ke PriceCacheEth, tapi TIDAK ADA yang baca tick itu — artinya posisi ETH
 * cuma di-cek tiap 60 detik via REST poll (CandleSchedulerEth), bukan
 * real-time per-detik seperti BNB. Class ini WAJIB ada untuk SL/TP/trailing
 * ETH bereaksi secepat BNB.
 *
 * paperTradingService SENGAJA tidak di-include — ETH skip paper trading
 * (konsisten dengan keputusan di CandleSchedulerEth).
 *
 * Gated: hanya jadi bean kalau trading.pair-eth.enabled=true
 */
@Service
@ConditionalOnProperty(name = "trading.pair-eth.enabled", havingValue = "true")
@RequiredArgsConstructor
@Log4j2
public class PriceMonitorServiceEth {

    private final PriceCacheEth priceCacheEth;
    private final OrderExecutorServiceEth orderExecutorService;

    // Throttle: track kapan terakhir check SL/TP
    private final AtomicLong lastCheckMs = new AtomicLong(0);
    private static final long CHECK_INTERVAL_MS = 1_000; // 1 detik

    /**
     * Register sebagai observer ke PriceCacheEth
     * Dipanggil saat bean ready
     */
    @PostConstruct
    public void init() {
        priceCacheEth.addObserver(this::onPriceTick);
        log.info("👁️ [ETH] PriceMonitorServiceEth registered as price observer");
    }

    /**
     * Dipanggil tiap price tick dari WebSocket ETH/USDC
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

        checkLivePosition(tick.getPrice());
    }

    private void checkLivePosition(BigDecimal price) {
        if (!orderExecutorService.isEnabled()) return;
        if (orderExecutorService.getOpenPosition() == null) return;

        orderExecutorService.updateTrailingFromWebSocket(price);

        // Re-fetch setelah trailing update — SL mungkin sudah berubah
        LivePosition position = orderExecutorService.getOpenPosition();
        if (position == null) return;

        if (position.isHitTakeProfit(price)) {
            log.info("🎯 [ETH][LIVE WebSocket] TP HIT: {} >= {}",
                    price, position.getTakeProfit());
            orderExecutorService.closePositionFromWebSocket(price, "TAKE_PROFIT");
            return;
        }

        if (position.isHitStopLoss(price)) {
            String reason = position.isTrailingActive()
                    ? "TRAILING_STOP" : "STOP_LOSS";
            log.warn("🛑 [ETH][LIVE WebSocket] {} HIT: {} <= {}",
                    reason, price, position.getStopLoss());
            orderExecutorService.closePositionFromWebSocket(price, reason);
        }
    }
}
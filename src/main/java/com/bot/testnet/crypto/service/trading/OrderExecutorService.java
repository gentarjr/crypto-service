package com.bot.testnet.crypto.service.trading;

import com.bot.testnet.crypto.model.LivePosition;
import com.bot.testnet.crypto.model.dto.Signal;
import com.bot.testnet.crypto.model.dto.StrategyType;
import com.bot.testnet.crypto.model.request.GetCurrentPriceRequest;
import com.bot.testnet.crypto.model.request.OcoOrderRequest;
import com.bot.testnet.crypto.model.request.PostBuyRequest;
import com.bot.testnet.crypto.model.request.PostSellRequest;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import com.bot.testnet.crypto.model.response.OcoOrderResponse;
import com.bot.testnet.crypto.service.TelegramNotificationService;
import com.bot.testnet.crypto.service.exchange.*;
import com.bot.testnet.crypto.service.risk.TrailingStopHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Live Order Executor — place real orders ke Binance
 *
 * Flow:
 * 1. Signal BUY → place market order
 * 2. Verify order filled
 * 3. Track position
 * 4. Monitor SL/TP tiap candle
 * 5. Close saat hit SL/TP
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class OrderExecutorService {

    private final BinanceService binanceService;
    private final BinanceBuyService binanceBuyService;
    private final BinanceSellService binanceSellService;
    private final TelegramNotificationService telegramService;
    private final TrailingStopHelper trailingStopHelper;
    private final BalanceService balanceService;
    private final BinanceOcoService binanceOcoService;

    @Value("${trading.live.enabled:false}")
    private boolean liveEnabled;

    @Value("${trading.pair.base:BNB}")
    private String baseCurrency;

    @Value("${trading.risk.max-slippage-percent:0.3}")
    private double maxSlippagePercent;

    @Value("${trading.pair.quote:USDT}")
    private String quoteCurrency;

    @Value("${trading.risk.max-daily-loss-percent:3.0}")
    private double maxDailyLossPercent;

    @Value("${trading.risk.max-consecutive-losses:3}")
    private int maxConsecutiveLosses;

    @Value("${trading.risk.cooldown-minutes:30}")
    private int cooldownMinutes;

    @Value("${trading.risk.trailing-atr-multiplier:1.5}")
    private double trailingAtrMultiplier;

    @Value("${trading.hours.enabled:true}")
    private boolean tradingHoursEnabled;

    @Value("${trading.hours.start-utc:8}")
    private int tradingHourStart;

    @Value("${trading.hours.end-utc:21}")
    private int tradingHourEnd;

    @Value("${trading.risk.partial-tp-enabled:true}")
    private boolean partialTpEnabled;

    @Value("${trading.risk.partial-tp-ratio:0.5}")
    private double partialTpRatio;

    // ═══════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════

    private LocalDate lastResetDate;
    private LivePosition openPosition;
    private final List<LivePosition> closedPositions = new ArrayList<>();
    private final ReentrantLock positionLock = new ReentrantLock();

    private BigDecimal dailyPnl = BigDecimal.ZERO;
    private int consecutiveLosses = 0;
    private boolean dailyHalted = false;
    private Instant lastCloseTime = null;
    private GetIndicatorResponse lastSnapshot = null;


    private boolean isWithinTradingHours() {
        if (!tradingHoursEnabled) return true;
        int hour = java.time.ZonedDateTime.now(ZoneId.of("Asia/Jakarta")).getHour();
        boolean within = hour >= tradingHourStart && hour < tradingHourEnd;
        if (!within) {
            log.info("🕐 [LIVE] Outside trading hours (UTC {})", hour);
        }
        return within;
    }

    // ═══════════════════════════════════════════════════
    // Public Methods
    // ═══════════════════════════════════════════════════

    /**
     * Dipanggil dari CandleScheduler tiap closed candle
     */

    public void onNewCandle(Signal signal, BigDecimal currentPrice,
                            GetIndicatorResponse snapshot) {
        if (!liveEnabled) {
            log.debug("Live trading disabled, skip");
            return;
        }

        checkAndResetDaily();
        this.lastSnapshot = snapshot;

        // Monitor open position
        if (openPosition != null) {
            monitorPosition(currentPrice);
        }

        // Safety checks
        if (dailyHalted) {
            log.info("🛑 Live trading HALTED today");
            return;
        }

        if (isDailyLossExceeded()) {
            haltDaily("Daily loss limit exceeded");
            return;
        }

        if (consecutiveLosses >= maxConsecutiveLosses) {
            haltDaily("Max consecutive losses (" + consecutiveLosses + "x)");
            return;
        }

        if (openPosition != null) {
            log.debug("Live position already open, skip new signal");
            return;
        }

        if (isCooldownActive()) {
            return;
        }

        if(!isWithinTradingHours()) return;

        // Execute signal
        if (signal != null && signal.isActionable()) {
            executeSignal(signal, currentPrice);
        }
    }

    /**
     * Update snapshot (dipanggil dari scheduler sebelum onNewCandle)
     */
    public void updateSnapshot(GetIndicatorResponse snapshot) {
        this.lastSnapshot = snapshot;
    }

    /**
     * Get open position
     */
    public LivePosition getOpenPosition() {
        return openPosition;
    }

    /**
     * Get closed positions
     */
    public List<LivePosition> getClosedPositions() {
        return new ArrayList<>(closedPositions);
    }

    /**
     * Is live trading enabled?
     */
    public boolean isEnabled() {
        return liveEnabled;
    }

    /**
     * Is live trading halted?
     */
    public boolean isHalted() {
        return dailyHalted;
    }

    // ✅ TAMBAH 3 GETTER INI:
    public int getClosedCount() {
        return closedPositions.size();
    }

    public BigDecimal getDailyPnl() {
        return dailyPnl;
    }

    public int getConsecutiveLosses() {
        return consecutiveLosses;
    }

    // ═══════════════════════════════════════════════════
    // Private: Signal Execution

    // ═══════════════════════════════════════════════════
    // Private: Signal Execution
    // ═══════════════════════════════════════════════════

    private void executeSignal(Signal signal, BigDecimal currentPrice) {
        switch (signal.getAction()) {
            case BUY -> executeBuy(signal, currentPrice);
            case SELL -> log.debug("SELL signal — spot only, no short");
            default -> log.debug("HOLD — no action");
        }
    }

    private void executeBuy(Signal signal, BigDecimal currentPrice) {
        log.info("🚀 [LIVE] Executing BUY signal...");

        try {
            // 1. Cek balance USDT tersedia
            BigDecimal availableUsdt = balanceService.getAvailableCapital();
            if (availableUsdt.compareTo(new BigDecimal("10")) < 0) {
                log.warn("⚠️ Insufficient USDT: ${}", availableUsdt);
                telegramService.sendMessage(
                        "⚠️ [LIVE] Insufficient Balance",
                        String.format(
                                "USDT tidak cukup untuk trading!\n\n" +
                                        "Available: <b>$%.2f</b>\n" +
                                        "Minimum: <b>$10</b>\n\n" +
                                        "Cek apakah ada BNB nyangkut di Binance!\n" +
                                        "⏰ %s WIB",
                                availableUsdt.doubleValue(),
                                formatTime()));
                return;
            }

            BigDecimal positionSize = signal.getPositionSize();
            if (positionSize == null || positionSize.compareTo(BigDecimal.ZERO) <= 0) {
                log.error("❌ Invalid position size: {}", positionSize);
                return;
            }

            if (availableUsdt.compareTo(positionSize) < 0) {
                log.warn("⚠️ Insufficient balance. Need: ${} | Available: ${}",
                        positionSize, availableUsdt);
                telegramService.sendMessage(
                        "⚠️ [LIVE] Insufficient Balance",
                        String.format("Need: $%.2f | Available: $%.2f",
                                positionSize.doubleValue(), availableUsdt.doubleValue()));
                return;
            }

            // 2. Hitung quantity BNB yang akan dibeli
            BigDecimal quantity = positionSize.divide(currentPrice, 6, RoundingMode.HALF_UP);
            quantity = roundQuantity(quantity);  // ✨ Round ke precision Binance

            if (!isQuantitySufficient(quantity)) {
                log.warn("⚠️ Quantity too small: {} BNB (min 0.01)", quantity);
                telegramService.sendMessage("⚠️ [LIVE] Order Skipped",
                        "Quantity too small: " + quantity + " BNB\nIncrease position size.");
                return;
            }

            log.info("📋 [LIVE] BUY {} {} @ ~${} (size: ${})",
                    quantity, baseCurrency, currentPrice, positionSize);

            BigDecimal signalPrice = signal.getPrice();
            if (signalPrice != null && signalPrice.compareTo(BigDecimal.ZERO) > 0) {
                try {
                    // ✅ Fetch harga real-time dari Binance, BUKAN dari snapshot
                    BigDecimal realTimePrice = binanceService.getCurrentPrice(
                            GetCurrentPriceRequest.builder()
                                    .base(baseCurrency)
                                    .quote(quoteCurrency)
                                    .build()).getPrice();

                    BigDecimal slippagePct = realTimePrice.subtract(signalPrice)
                            .divide(signalPrice, 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .abs();

                    BigDecimal maxSlippagePct = BigDecimal.valueOf(maxSlippagePercent);

                    if (slippagePct.compareTo(maxSlippagePct) > 0) {
                        log.warn("⚠️ Slippage too high: signal=${} → real-time=${} ({}%)",
                                signalPrice, realTimePrice, slippagePct);
                        telegramService.sendMessage(
                                "⚠️ [LIVE] Order Skipped — High Slippage",
                                String.format(
                                        "Signal price: $%.4f\n" +
                                                "Real-time:    $%.4f\n" +
                                                "Slippage: %.2f%% (max %.2f%%)\n\n" +
                                                "Order cancelled.\n⏰ %s WIB",
                                        signalPrice.doubleValue(),
                                        realTimePrice.doubleValue(),
                                        slippagePct.doubleValue(),
                                        maxSlippagePct.doubleValue(),
                                        formatTime()));
                        return;
                    }

                    // Update currentPrice ke harga real-time supaya entry akurat
                    currentPrice = realTimePrice;
                    log.info("✅ Slippage ok: {}% (using real-time price ${})",
                            slippagePct, realTimePrice);
                    BigDecimal originalEntry = signal.getPrice();
                    BigDecimal originalSL = signal.getStopLoss();
                    BigDecimal originalTP = signal.getTakeProfit();

                    if (originalSL != null && originalEntry != null
                            && originalEntry.compareTo(BigDecimal.ZERO) > 0) {

                        // Hitung jarak SL dan TP dari signal original
                        BigDecimal slDistance = originalEntry.subtract(originalSL).abs();
                        BigDecimal tpDistance = originalTP != null
                                ? originalTP.subtract(originalEntry).abs()
                                : BigDecimal.ZERO;

                        // Apply jarak yang sama dari actual entry
                        BigDecimal adjustedSL = realTimePrice.subtract(slDistance);
                        BigDecimal adjustedTP = tpDistance.compareTo(BigDecimal.ZERO) > 0
                                ? realTimePrice.add(tpDistance)
                                : null;

                        signal.setStopLoss(adjustedSL);
                        if (adjustedTP != null) signal.setTakeProfit(adjustedTP);

                        log.info("📐 SL/TP adjusted: original entry ${} → actual ${} | SL: ${} → ${} | TP: ${} → ${}",
                                originalEntry, realTimePrice,
                                originalSL, adjustedSL,
                                originalTP, adjustedTP);
                    }
                } catch (Exception e) {
                    log.warn("⚠️ Cannot fetch real-time price, using snapshot price: {}",
                            e.getMessage());
                }
            }

            BigDecimal limitPrice = currentPrice.multiply(
                            BigDecimal.ONE.add(
                                    BigDecimal.valueOf(maxSlippagePercent / 100)))
                    .setScale(2, RoundingMode.DOWN);

            BigDecimal adjustedQuantity = positionSize
                    .divide(limitPrice, 6, RoundingMode.HALF_UP)
                    .setScale(2, RoundingMode.DOWN);

            log.info("📐 Quantity adjusted for limit price: {} → {} BNB (limitPrice=${})",
                    quantity, adjustedQuantity, limitPrice);

            quantity = adjustedQuantity;

            var orderResult = binanceBuyService.placeMarketBuyOrder(
                    buildBuyRequest(quantity, limitPrice));

            // 4. Verify order filled
            if (!"FILLED".equals(orderResult.getStatus())) {
                log.error("❌ [LIVE] Order not filled: {}", orderResult.getStatus());
                telegramService.sendMessage(
                        "❌ [LIVE] Order Failed",
                        "Status: " + orderResult.getStatus() +
                                "\nError: " + orderResult.getErrorMessage());
                return;
            }

            // ─── Actual Entry Price ───────────────────────────────
            // Default: pakai currentPrice (realTimePrice yang sudah di-update di CHECK 1)
            // Ini paling akurat karena diambil tepat sebelum order dikirim
            BigDecimal actualEntry = currentPrice;

            if (orderResult.getFilledAmount() != null
                    && orderResult.getFilledAmount().compareTo(BigDecimal.ZERO) > 0
                    && orderResult.getBalanceBefore() != null
                    && orderResult.getBalanceAfter() != null) {

                BigDecimal usdtSpent = orderResult.getBalanceBefore()
                        .subtract(orderResult.getBalanceAfter());

                if (usdtSpent.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal calculatedEntry = usdtSpent.divide(
                            orderResult.getFilledAmount(), 6, RoundingMode.HALF_UP);

                    // Sanity check: kalau calculatedEntry jauh dari realtime (> 1%)
                    // berarti ada fee distortion → tetap pakai realtime
                    BigDecimal diffPct = calculatedEntry.subtract(currentPrice)
                            .abs()
                            .divide(currentPrice, 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));

                    if (diffPct.compareTo(new BigDecimal("1.0")) < 0) {
                        actualEntry = calculatedEntry;
                        log.info("📌 Actual entry (balance diff): ${} (diff: {}%)",
                                actualEntry, diffPct);
                    } else {
                        // Fee distortion detected → pakai realtime
                        log.warn("⚠️ Balance diff entry ${} too far from realtime ${} ({}%)" +
                                " → using realtime", calculatedEntry, currentPrice, diffPct);
                    }
                }
            } else {
                log.info("📌 Actual entry (realtime): ${}", actualEntry);
            }

            // ─── Post-fill Slippage Check ─────────────────────────
            // Bandingkan actualEntry vs currentPrice (realTimePrice)
            // Kalau terlalu jauh → sell balik, jangan buka posisi
            BigDecimal postFillSlippage = actualEntry.subtract(currentPrice)
                    .abs()
                    .divide(currentPrice, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            log.info("📊 Post-fill slippage: {}%", postFillSlippage);

            if (postFillSlippage.compareTo(BigDecimal.valueOf(maxSlippagePercent)) > 0) {
                log.warn("🚨 Post-fill slippage {}% > {}% — closing immediately!",
                        postFillSlippage, maxSlippagePercent);
                telegramService.sendMessage(
                        "⚠️ [LIVE] Order Closed — Post-fill Slippage Too High",
                        String.format(
                                "Requested: $%.4f\n" +
                                        "Actual:    $%.4f\n" +
                                        "Slippage:  %.2f%% (max %.2f%%)\n\n" +
                                        "Order DIBATALKAN — langsung close.\n" +
                                        "⏰ %s WIB",
                                currentPrice.doubleValue(),
                                actualEntry.doubleValue(),
                                postFillSlippage.doubleValue(),
                                maxSlippagePercent,
                                formatTime()));
                try {
                    BigDecimal sellAmt = orderResult.getFilledAmount() != null
                            ? orderResult.getFilledAmount().setScale(2, RoundingMode.DOWN)
                            : quantity.setScale(2, RoundingMode.DOWN);
                    binanceSellService.placeMarketSellOrder(
                            PostSellRequest.builder()
                                    .base(baseCurrency)
                                    .quote(quoteCurrency)
                                    .amount(sellAmt)
                                    .build());
                    log.info("✅ Post-fill sell executed: {} BNB", sellAmt);
                } catch (Exception sellEx) {
                    log.error("❌ Post-fill sell failed: {}", sellEx.getMessage());
                    telegramService.sendMessage(
                            "🚨 CRITICAL — Close Manual!",
                            "Gagal close post-fill!\n" +
                                    "Close manual di Binance!\n" +
                                    "⏰ " + formatTime() + " WIB");
                }
                return; // ← jangan buka posisi
            }

            BigDecimal originalEntry = signal.getPrice();
            BigDecimal originalSL    = signal.getStopLoss();
            BigDecimal adjustedSL    = originalSL;
            BigDecimal adjustedTP    = signal.getTakeProfit();

            if (originalEntry != null && originalEntry.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal slDistance = originalEntry.subtract(originalSL).abs();
                BigDecimal tpDistance = signal.getTakeProfit() != null
                        ? signal.getTakeProfit().subtract(originalEntry).abs()
                        : BigDecimal.ZERO;

                adjustedSL = actualEntry.subtract(slDistance);
                if (tpDistance.compareTo(BigDecimal.ZERO) > 0) {
                    adjustedTP = actualEntry.add(tpDistance);
                }

                if (adjustedSL.compareTo(originalSL) != 0) {
                    log.info("📐 SL/TP adjusted: entry ${} → actual ${} | " +
                                    "SL: ${} → ${} | TP: ${} → ${}",
                            originalEntry, actualEntry,
                            originalSL, adjustedSL,
                            signal.getTakeProfit(), adjustedTP);
                }
            }

            positionLock.lock();
            try {
                openPosition = LivePosition.builder()
                        .id(UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                        .binanceOrderId(orderResult.getOrderId())
                        .strategy(signal.getStrategy())
                        .entryPrice(actualEntry)
                        .requestedPrice(currentPrice)
                        .quantity(quantity)
                        .positionValue(positionSize)
                        .stopLoss(adjustedSL)
                        .initialStopLoss(adjustedSL)
                        .takeProfit(adjustedTP)
                        .highestPrice(actualEntry)
                        .trailingActive(false)
                        .status("OPEN")
                        .openTime(ZonedDateTime.now(ZoneId.of("Asia/Jakarta")).toInstant())
                        .build();

                log.info("✅ [LIVE] Position OPENED #{}: entry=${}, qty={}, SL=${}, TP={}",
                        openPosition.getId(),
                        actualEntry,
                        quantity,
                        signal.getStopLoss(),
                        signal.getTakeProfit() != null ? "$" + signal.getTakeProfit() : "TRAILING");

                sendLivePositionOpenedNotif(openPosition);
                if (signal.getTakeProfit() != null && signal.getStopLoss() != null) {
                    try {
                        OcoOrderResponse ocoResult = binanceOcoService.placeOcoOrder(
                                OcoOrderRequest.builder()
                                        .base(baseCurrency)
                                        .quote(quoteCurrency)
                                        .quantity(openPosition.getQuantity().setScale(2, RoundingMode.DOWN))
                                        .takeProfitPrice(adjustedTP.setScale(2, RoundingMode.HALF_UP))
                                        .stopLossPrice(adjustedSL.setScale(2, RoundingMode.DOWN))
                                        .build());

                        if ("SUCCESS".equals(ocoResult.getStatus())) {
                            openPosition.setOcoOrderListId(ocoResult.getOrderListId());
                            openPosition.setLastOcoSL(adjustedSL);
                            log.info("✅ OCO placed: {}", ocoResult.getOrderListId());
                            telegramService.sendMessage(
                                    "🛡️ [LIVE] OCO Protection Active",
                                    String.format(
                                            "SL dan TP terpasang di Binance.\n" +
                                                    "Aman meski bot restart!\n\n" +
                                                    "🎯 TP: <b>$%.2f</b>\n" +
                                                    "🛑 SL: <b>$%.2f</b>\n" +
                                                    "⏰ %s WIB",
                                            signal.getTakeProfit().doubleValue(),
                                            signal.getStopLoss().doubleValue(),
                                            formatTime()));
                        } else if ("SKIPPED".equals(ocoResult.getStatus())) {
                            log.info("ℹ️ OCO skipped (testnet mode)");
                        } else {
                            log.warn("⚠️ OCO failed: {}", ocoResult.getErrorMessage());
                            telegramService.sendMessage(
                                    "⚠️ [LIVE] OCO Gagal — Monitor Manual!",
                                    String.format(
                                            "OCO tidak berhasil!\n" +
                                                    "SL/TP hanya di memory bot.\n\n" +
                                                    "Jangan restart bot saat ada posisi!\n" +
                                                    "⏰ %s WIB",
                                            formatTime()));
                        }
                    } catch (Exception e) {
                        log.error("❌ OCO error: {}", e.getMessage());
                    }
                }
            } finally {
                positionLock.unlock();
            }

        } catch (Exception e) {
            log.error("❌ BUY error: {}", e.getMessage());
            telegramService.sendMessage(
                    "❌ BUY Order GAGAL",
                    String.format(
                            "Gagal eksekusi BUY order!\n\n" +
                                    "Signal: %s\n" +
                                    "Price: $%s\n" +
                                    "Error: %s\n\n" +
                                    "⚠️ Cek Binance manual!\n" +
                                    "⏰ %s WIB",
                            signal.getStrategy(),
                            currentPrice,
                            e.getMessage(),
                            formatTime()));
        }
    }

    // ═══════════════════════════════════════════════════
    // Private: Position Monitoring
    // ═══════════════════════════════════════════════════

    private void monitorPosition(BigDecimal currentPrice) {
        if (openPosition == null) return;

        openPosition.updateHighestPrice(currentPrice);

        checkPartialTakeProfit(currentPrice);

        if (openPosition.getOpenTime() != null) {
            long minutesOpen = Duration.between(
                    openPosition.getOpenTime(),
                    Instant.now()).toMinutes();

            if (minutesOpen > 240 && !openPosition.isTrailingActive()) {
                log.warn("⏰ [LIVE] Position #{} stagnant for {}m without progress, force close",
                        openPosition.getId(), minutesOpen);
                telegramService.sendMessage(
                        "⏰ [LIVE] Force Close — Stagnant Position",
                        String.format(
                                "Position #%s open %d minutes without trailing activation\n" +
                                        "No progress → closing at market price\n" +
                                        "⏰ %s WIB",
                                openPosition.getId(), minutesOpen, formatTime()));
                closeLivePosition(currentPrice, "TIMEOUT_NO_PROGRESS");
                return;
            }
        }

        // Update trailing SL (EMA strategy only)
        if (lastSnapshot != null
                && openPosition.getStrategy() == StrategyType.EMA_CROSSOVER) {
            boolean trailingUpdated = trailingStopHelper.update(
                    openPosition, currentPrice, lastSnapshot.getAtr(), "LIVE");

            if (trailingUpdated && openPosition.getOcoOrderListId() != null) {
                BigDecimal slChange = openPosition.getStopLoss()
                        .subtract(openPosition.getLastOcoSL() != null
                                ? openPosition.getLastOcoSL()
                                : openPosition.getInitialStopLoss())
                        .abs();
                if (slChange.compareTo(new BigDecimal("0.50")) >= 0) {
                    updateOcoAfterTrailing(openPosition);
                    openPosition.setLastOcoSL(openPosition.getStopLoss());
                }
            }
        }

        // Check TP (BB strategy)
        if (openPosition.isHitTakeProfit(currentPrice)) {
            log.info("🎯 [LIVE] TP HIT: {} >= {}", currentPrice, openPosition.getTakeProfit());
            closeLivePosition(currentPrice, "TAKE_PROFIT");
            return;
        }

        // Check SL
        if (openPosition.isHitStopLoss(currentPrice)) {
            String reason = openPosition.isTrailingActive() ? "TRAILING_STOP" : "STOP_LOSS";
            log.warn("🛑 [LIVE] {} HIT: {} <= {}", reason, currentPrice, openPosition.getStopLoss());
            closeLivePosition(currentPrice, reason);
        }
    }

    /**
     * Monitor posisi dengan harga real-time
     * Dipanggil tiap 1 menit dari CandleScheduler
     * Supaya SL/TP tidak miss kalau harga spike dalam 1 candle
     */
    public void monitorPositionRealtime(BigDecimal realtimePrice) {
        if (!liveEnabled || openPosition == null) return;

        openPosition.updateHighestPrice(realtimePrice);
        checkPartialTakeProfit(realtimePrice);

        log.info("📡 [LIVE] RT monitor: price=${} SL=${} TP={}",
                realtimePrice,
                openPosition.getStopLoss(),
                openPosition.getTakeProfit() != null
                        ? "$" + openPosition.getTakeProfit() : "TRAIL");

        // Check SL
        if (openPosition.isHitStopLoss(realtimePrice)) {
            String reason = openPosition.isTrailingActive()
                    ? "TRAILING_STOP" : "STOP_LOSS";
            log.warn("🛑 [LIVE] {} HIT (realtime): ${} <= ${}",
                    reason, realtimePrice, openPosition.getStopLoss());
            closeLivePosition(realtimePrice, reason);
            return;
        }

        // Check TP
        if (openPosition.isHitTakeProfit(realtimePrice)) {
            log.info("🎯 [LIVE] TP HIT (realtime): ${} >= ${}",
                    realtimePrice, openPosition.getTakeProfit());
            closeLivePosition(realtimePrice, "TAKE_PROFIT");
            return;
        }

        // Update trailing SL (EMA strategy only)
        if (lastSnapshot != null
                && openPosition.getStrategy() == StrategyType.EMA_CROSSOVER) {
            boolean trailingUpdated = trailingStopHelper.update(
                    openPosition, realtimePrice, lastSnapshot.getAtr(), "LIVE");

            if (trailingUpdated && openPosition.getOcoOrderListId() != null) {
                BigDecimal slChange = openPosition.getStopLoss()
                        .subtract(openPosition.getLastOcoSL() != null
                                ? openPosition.getLastOcoSL()
                                : openPosition.getInitialStopLoss())
                        .abs();
                if (slChange.compareTo(new BigDecimal("0.50")) >= 0) {
                    updateOcoAfterTrailing(openPosition);
                    openPosition.setLastOcoSL(openPosition.getStopLoss());
                }
            }
        }
    }

    /**
     * Partial Take Profit:
     * Saat harga mencapai 50% jarak ke TP → sell 50% posisi
     * Sisa 50% tetap running dengan trailing SL
     */
    private void checkPartialTakeProfit(BigDecimal currentPrice) {
        if (!partialTpEnabled) return;
        if (openPosition == null) return;
        if (openPosition.isPartialTpExecuted()) return;
        if (openPosition.getTakeProfit() == null) return;

        BigDecimal entry = openPosition.getEntryPrice();
        BigDecimal tp    = openPosition.getTakeProfit();
        BigDecimal tpDistance = tp.subtract(entry);
        if (tpDistance.compareTo(BigDecimal.ZERO) <= 0) return;

        BigDecimal partialTpLevel = entry.add(
                tpDistance.multiply(BigDecimal.valueOf(partialTpRatio)));

        if (currentPrice.compareTo(partialTpLevel) < 0) return;

        // ✅ Pakai positionLock supaya tidak race condition
        if (!positionLock.tryLock()) return; // kalau tidak bisa lock, skip
        try {
            // ✅ Double check setelah lock (Thread safety!)
            if (openPosition == null) return;
            if (openPosition.isPartialTpExecuted()) return;

            // ✅ Set flag PERTAMA sebelum apapun
            // Supaya thread lain tidak masuk
            openPosition.setPartialTpExecuted(true);

            log.info("🎯 [LIVE] Partial TP triggered: price=${} >= level=${}",
                    currentPrice, partialTpLevel);

            BigDecimal partialQty = openPosition.getQuantity()
                    .multiply(BigDecimal.valueOf(partialTpRatio))
                    .setScale(2, RoundingMode.DOWN);

            if (partialQty.compareTo(new BigDecimal("0.01")) < 0) {
                log.warn("⚠️ Partial TP qty too small: {} — skip", partialQty);
                openPosition.setPartialTpExecuted(false); // reset flag
                return;
            }

            try {
                var sellResult = binanceSellService.placeMarketSellOrder(
                        PostSellRequest.builder()
                                .base(baseCurrency)
                                .quote(quoteCurrency)
                                .amount(partialQty)
                                .build());

                if ("FILLED".equals(sellResult.getStatus())) {
                    // Update quantity remaining
                    BigDecimal remainingQty = openPosition.getQuantity()
                            .subtract(partialQty)
                            .setScale(2, RoundingMode.DOWN);
                    openPosition.setQuantity(remainingQty);

                    // Hitung partial profit
                    BigDecimal partialProfit = currentPrice
                            .subtract(openPosition.getEntryPrice())
                            .multiply(partialQty);
                    BigDecimal partialFee = currentPrice.multiply(partialQty)
                            .multiply(new BigDecimal("0.00075"));
                    BigDecimal partialNetProfit = partialProfit.subtract(partialFee);

                    openPosition.setPartialTpPrice(currentPrice);
                    openPosition.setPartialTpQuantity(partialQty);
                    openPosition.setPartialTpPnl(partialNetProfit);

                    // Pindah SL ke breakeven + fee
                    BigDecimal newSL = openPosition.getEntryPrice()
                            .add(openPosition.getEntryPrice()
                                    .multiply(new BigDecimal("0.002")))
                            .setScale(2, RoundingMode.HALF_UP);
                    openPosition.ratchetStopLoss(newSL);

                    log.info("✅ Partial TP: sold {} BNB @ ${}, net profit=${}, " +
                                    "remaining={} BNB, SL→${}",
                            partialQty, currentPrice,
                            partialNetProfit, remainingQty, newSL);

                    telegramService.sendMessage(
                            "🎯 [LIVE] Partial Take Profit!",
                            String.format(
                                    "Position #%s\n\n" +
                                            "Sold: <b>%.2f BNB</b> @ <b>$%.2f</b>\n" +
                                            "Profit: <b>+$%.4f</b>\n\n" +
                                            "Remaining: <b>%.2f BNB</b>\n" +
                                            "SL moved to: <b>$%.2f</b> (breakeven)\n" +
                                            "TP target: <b>$%.2f</b>\n\n" +
                                            "⏰ %s WIB",
                                    openPosition.getId(),
                                    partialQty.doubleValue(),
                                    currentPrice.doubleValue(),
                                    partialNetProfit.doubleValue(),
                                    remainingQty.doubleValue(),
                                    newSL.doubleValue(),
                                    openPosition.getTakeProfit().doubleValue(),
                                    formatTime()));
                } else {
                    // Sell gagal → reset flag supaya bisa retry
                    log.error("❌ Partial TP sell failed: {}", sellResult.getStatus());
                    openPosition.setPartialTpExecuted(false);
                }
            } catch (Exception e) {
                log.error("❌ Partial TP error: {}", e.getMessage());
                openPosition.setPartialTpExecuted(false); // reset flag
            }
        } finally {
            positionLock.unlock();
        }
    }

    private void closeLivePosition(BigDecimal exitPrice, String reason) {
        positionLock.lock();
        try {
            if (openPosition == null) return;

            // ✅ Clear openPosition SEGERA di awal
            // Mencegah WebSocket/Scheduler trigger close lagi (loop!)
            LivePosition positionToClose = openPosition;
            openPosition = null;

            log.info("🔄 [LIVE] Closing position #{} ({})...",
                    positionToClose.getId(), reason);

            // Cancel OCO kalau ada
            if (positionToClose.getOcoOrderListId() != null) {
                log.info("🗑️ Cancelling OCO before close...");
                binanceOcoService.cancelOcoOrder(
                        baseCurrency + quoteCurrency,
                        positionToClose.getOcoOrderListId());
                // Tunggu 2 detik supaya OCO cancel diproses
                try { Thread.sleep(2000); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }

            // Cek BNB balance setelah cancel OCO
            BigDecimal actualBnbBalance;
            try {
                actualBnbBalance = balanceService.getAvailableBnb();
            } catch (Exception e) {
                log.warn("Cannot fetch BNB balance: {}", e.getMessage());
                actualBnbBalance = positionToClose.getQuantity();
            }

            // ✅ Kalau BNB < 0.01 → OCO sudah eksekusi duluan
            // Skip manual SELL
            if (actualBnbBalance == null
                    || actualBnbBalance.compareTo(new BigDecimal("0.01")) < 0) {
                log.info("✅ BNB = 0 — position already closed by OCO");
                telegramService.sendMessage(
                        "✅ [LIVE] Position Closed by OCO",
                        String.format(
                                "Position #%s closed by Binance OCO.\n" +
                                        "Reason: %s\n" +
                                        "⏰ %s WIB",
                                positionToClose.getId(),
                                reason,
                                formatTime()));
                // Update stats
                updateCloseStats(positionToClose, exitPrice, reason);
                return;
            }

            // BNB masih ada → SELL manual
            try {
                BigDecimal feeBuffer = actualBnbBalance
                        .multiply(new BigDecimal("0.00075"))
                        .setScale(4, RoundingMode.UP);
                BigDecimal sellAmount = actualBnbBalance
                        .subtract(feeBuffer)
                        .setScale(2, RoundingMode.DOWN);
                BigDecimal minQty = new BigDecimal("0.01");
                if (sellAmount.compareTo(minQty) < 0) {
                    sellAmount = minQty;
                }

                log.info("💰 Sell amount: {} BNB (balance: {}, fee buffer: {})",
                        sellAmount, actualBnbBalance, feeBuffer);

                var sellResult = binanceSellService.placeMarketSellOrder(
                        PostSellRequest.builder()
                                .base(baseCurrency)
                                .quote(quoteCurrency)
                                .amount(sellAmount)
                                .build());

                if (!"FILLED".equals(sellResult.getStatus())) {
                    log.error("❌ [LIVE] SELL failed: {}", sellResult.getStatus());
                    telegramService.sendMessage(
                            "🚨 [LIVE] SELL FAILED — MANUAL ACTION NEEDED!",
                            String.format(
                                    "Position #%s could not be closed!\n" +
                                            "Reason: %s\nStatus: %s\n\n" +
                                            "⚠️ Close manually on Binance!\n" +
                                            "⏰ %s WIB",
                                    positionToClose.getId(),
                                    reason,
                                    sellResult.getStatus(),
                                    formatTime()));
                    // openPosition sudah null di atas → tidak loop ✅
                    return;
                }

                // SELL berhasil → update stats
                updateCloseStats(positionToClose, exitPrice, reason);

            } catch (Exception e) {
                log.error("❌ SELL error: {}", e.getMessage());
                telegramService.sendMessage(
                        "🚨 [LIVE] SELL ERROR — MANUAL ACTION NEEDED!",
                        String.format(
                                "Position #%s error!\n" +
                                        "Error: %s\n\n" +
                                        "⚠️ Close manually on Binance!\n" +
                                        "⏰ %s WIB",
                                positionToClose.getId(),
                                e.getMessage(),
                                formatTime()));
                // openPosition sudah null → tidak loop ✅
            }

        } finally {
            positionLock.unlock();
        }
    }

    // ═══════════════════════════════════════════════════
    // Private: Risk Checks
    // ═══════════════════════════════════════════════════

    private boolean isDailyLossExceeded() {
        if (dailyPnl.compareTo(BigDecimal.ZERO) >= 0) return false;
        // Ambil balance dari Binance untuk hitung % loss
        try {
            BigDecimal balance = balanceService.getTotalCapital();
            BigDecimal maxLoss = balance.multiply(
                    BigDecimal.valueOf(maxDailyLossPercent / 100));
            return dailyPnl.abs().compareTo(maxLoss) > 0;
        } catch (Exception e) {
            log.error("Cannot check daily loss: {}", e.getMessage());
            return false;
        }
    }

    private boolean isCooldownActive() {
        if (lastCloseTime == null) return false;
        Instant cooldownEnd = lastCloseTime.plus(Duration.ofMinutes(cooldownMinutes));
        boolean active = Instant.now().isBefore(cooldownEnd);
        if (active) {
            long remaining = Duration.between(Instant.now(), cooldownEnd).getSeconds();
            log.info("⏳ [LIVE] Cooldown: {}m {}s remaining",
                    remaining / 60, remaining % 60);
        }
        return active;
    }

    private void haltDaily(String reason) {
        log.warn("🛑 [LIVE] HALTED: {}", reason);
        dailyHalted = true;

        positionLock.lock();
        try {
            if (openPosition != null) {
                try {
                    BigDecimal currentPrice = binanceService
                            .getCurrentPrice(GetCurrentPriceRequest.builder()
                                    .base(baseCurrency)
                                    .quote(quoteCurrency)
                                    .build()).getPrice();
                    closeLivePosition(currentPrice, "FORCED_EXIT");
                } catch (Exception e) {
                    log.error("Error force closing: {}", e.getMessage());
                }
            }
        } finally {
            positionLock.unlock();
        }

        telegramService.sendMessage(
                "🛑 [LIVE] Trading HALTED",
                String.format(
                        "Reason: %s\n" +
                                "Daily P&L: $%.4f\n" +
                                "Consecutive losses: %d\n\n" +
                                "Will resume tomorrow.",
                        reason,
                        dailyPnl.doubleValue(),
                        consecutiveLosses));
    }

    // ═══════════════════════════════════════════════════
    // Private: Build Requests
    // ═══════════════════════════════════════════════════

    private PostBuyRequest buildBuyRequest(BigDecimal quantity, BigDecimal limit) {
        return PostBuyRequest.builder()
                .base(baseCurrency)
                .quote(quoteCurrency)
                .amount(quantity)
                .limitPrice(limit)
                .build();
    }

    private PostSellRequest buildSellRequest(BigDecimal quantity) {
        return PostSellRequest.builder()
                .base(baseCurrency)
                .quote(quoteCurrency)
                .amount(quantity)
                .build();
    }

    // ═══════════════════════════════════════════════════
    // Private: Notifications
    // ═══════════════════════════════════════════════════

    private void sendLivePositionOpenedNotif(LivePosition position) {
        telegramService.sendMessage(
                "🟢 [LIVE] Position Opened",
                String.format(
                        "🆔 #%s | %s\n\n" +
                                "💰 Entry: <b>$%.4f</b>\n" +
                                "📦 Qty:   <b>%.6f %s</b>\n" +
                                "💵 Value: <b>$%.2f</b>\n\n" +
                                "🛑 SL: <b>$%.4f</b>\n" +
                                "🎯 Exit: <b>%s</b>\n\n" +
                                "⏰ %s WIB",
                        position.getId(),
                        position.getStrategy(),
                        position.getEntryPrice().doubleValue(),
                        position.getQuantity().doubleValue(),
                        baseCurrency,
                        position.getPositionValue().doubleValue(),
                        position.getStopLoss().doubleValue(),
                        position.getStrategy() == StrategyType.EMA_CROSSOVER
                                ? "TRAILING SL"
                                : String.format("$%.4f (TP)", position.getTakeProfit().doubleValue()),
                        formatTime()));
    }

    private void updateCloseStats(LivePosition position,
                                  BigDecimal exitPrice,
                                  String reason) {
        BigDecimal feeRate = new BigDecimal("0.00075");

        // ✅ Hitung P&L untuk remaining quantity (bukan full quantity)
        BigDecimal remainingQty = position.getQuantity();
        BigDecimal pnl = exitPrice.subtract(position.getEntryPrice())
                .multiply(remainingQty);

        // ✅ Fee hanya untuk remaining sell (buy fee sudah diperhitungkan terpisah)
        // Buy fee: proporsional dengan remaining qty
        BigDecimal remainingValue = position.getEntryPrice().multiply(remainingQty);
        BigDecimal buyFee  = remainingValue.multiply(feeRate);
        BigDecimal sellFee = exitPrice.multiply(remainingQty).multiply(feeRate);
        BigDecimal totalFee = buyFee.add(sellFee);
        BigDecimal pnlAfterFee = pnl.subtract(totalFee);

        // ✅ Tambah partial TP profit kalau ada
        if (position.isPartialTpExecuted()
                && position.getPartialTpPnl() != null) {
            pnlAfterFee = pnlAfterFee.add(position.getPartialTpPnl());
            log.info("📊 Including partial TP profit: ${}",
                    position.getPartialTpPnl());
        }

        // ✅ P&L percent dari positionValue awal (bukan remaining)
        BigDecimal pnlPercent = position.getPositionValue()
                .compareTo(BigDecimal.ZERO) > 0
                ? pnlAfterFee.divide(position.getPositionValue(),
                        6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        boolean isWin = pnlAfterFee.compareTo(BigDecimal.ZERO) > 0;

        position.setClosePrice(exitPrice);
        position.setFee(totalFee);
        position.setRealizedPnl(pnlAfterFee);
        position.setPnlAfterFee(pnlAfterFee);
        position.setPnlPercent(pnlPercent);
        position.setCloseReason(reason);
        position.setCloseTime(ZonedDateTime.now(
                ZoneId.of("Asia/Jakarta")).toInstant());
        position.setStatus("CLOSED");

        dailyPnl = dailyPnl.add(pnlAfterFee);
        consecutiveLosses = isWin ? 0 : consecutiveLosses + 1;
        lastCloseTime = ZonedDateTime.now(
                ZoneId.of("Asia/Jakarta")).toInstant();
        closedPositions.add(position);

        log.info("✅ [LIVE] Position CLOSED #{}: {} | net=${} ({}%)" +
                        (position.isPartialTpExecuted()
                                ? " [includes partial TP: $" + position.getPartialTpPnl() + "]"
                                : ""),
                position.getId(), reason,
                String.format("%.4f", pnlAfterFee.doubleValue()),
                String.format("%.2f", pnlPercent.doubleValue()));

        sendLivePositionClosedNotif(position);
    }

    // SESUDAH:
    private void sendLivePositionClosedNotif(LivePosition position) {
        boolean isWin = position.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0;
        String emoji  = isWin ? "✅" : "❌";

        // Gross P&L = pnlAfterFee + fee
        BigDecimal fee      = position.getFee() != null
                ? position.getFee() : BigDecimal.ZERO;
        BigDecimal grossPnl = position.getRealizedPnl().add(fee);

        telegramService.sendMessage(
                emoji + " [LIVE] " + position.getCloseReason(),
                String.format(
                        "🆔 #%s | %s\n\n" +
                                "💰 Entry:  <b>$%.4f</b>\n" +
                                "💰 Exit:   <b>$%.4f</b>\n\n" +
                                "%s Gross P&L: <b>%s$%.4f</b>\n" +
                                "💸 Fee:      <b>-$%.4f</b>\n" +
                                "📊 Net P&L:  <b>%s$%.4f (%s%.2f%%)</b>\n\n" +
                                "📈 Today P&L: <b>%s$%.4f</b>\n" +
                                "🔁 Consec losses: <b>%d</b>\n\n" +
                                "⏰ %s WIB",
                        position.getId(),
                        position.getStrategy(),
                        position.getEntryPrice().doubleValue(),
                        position.getClosePrice().doubleValue(),
                        isWin ? "📈" : "📉",
                        grossPnl.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "",
                        grossPnl.doubleValue(),
                        fee.doubleValue(),
                        position.getRealizedPnl().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "",
                        position.getRealizedPnl().doubleValue(),
                        position.getPnlPercent() != null
                                && position.getPnlPercent().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "",
                        position.getPnlPercent() != null
                                ? position.getPnlPercent().doubleValue() : 0.0,
                        dailyPnl.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "",
                        dailyPnl.doubleValue(),
                        consecutiveLosses,
                        formatTime()));
    }

    private String formatTime() {
        return java.time.LocalDateTime.now(ZoneId.of("Asia/Jakarta"))
                .format(java.time.format.DateTimeFormatter
                        .ofPattern("dd-MM-yyyy HH:mm:ss"));
    }

    /**
     * Close position dari WebSocket thread (real-time)
     * Public supaya bisa dipanggil dari PriceMonitorService
     */
    public void closePositionFromWebSocket(BigDecimal exitPrice, String reason) {
        closeLivePosition(exitPrice, reason);
    }

    private BigDecimal roundQuantity(BigDecimal quantity) {
        // BNB minimum order: 0.01 BNB, step size: 0.01
        // Selalu round DOWN supaya tidak exceed balance
        return quantity.setScale(2, RoundingMode.DOWN);
    }

    private boolean isQuantitySufficient(BigDecimal quantity) {
        // BNB minimum notional: $10 USDT, minimum qty: 0.01 BNB
        return quantity.compareTo(new BigDecimal("0.01")) >= 0;
    }

    private void checkAndResetDaily() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Jakarta"));
        if (lastResetDate == null || !lastResetDate.equals(today)) {
            log.info("📅 [LIVE] Daily stats reset for {}", today);

            boolean wasHalted = dailyHalted; // ✅ Simpan state SEBELUM reset

            dailyPnl = BigDecimal.ZERO;
            dailyHalted = false;
            consecutiveLosses = 0;
            lastResetDate = today;

            if (wasHalted) {  // ✅ Cek state yang lama
                telegramService.sendMessage(
                        "✅ [LIVE] Bot Resumed",
                        String.format(
                                "New day — daily limits reset\n\n" +
                                        "📅 Date: %s\n" +
                                        "💰 Ready to trade again!\n" +
                                        "⏰ %s WIB",
                                today, formatTime()));
            }
        }
    }

    /**
     * Update trailing SL dari WebSocket (tiap detik)
     * Dipanggil dari PriceMonitorService
     */
    public void updateTrailingFromWebSocket(BigDecimal price) {
        if (!liveEnabled || openPosition == null) return;
        if (lastSnapshot == null) return;
        if (openPosition.getStrategy() != StrategyType.EMA_CROSSOVER) return;

        openPosition.updateHighestPrice(price);
        trailingStopHelper.update(openPosition, price, lastSnapshot.getAtr(), "LIVE-WS");
        checkPartialTakeProfit(price);
    }

    private void updateOcoAfterTrailing(LivePosition position) {
        try {
            String oldOcoId = position.getOcoOrderListId();

            binanceOcoService.cancelOcoOrder(
                    baseCurrency + quoteCurrency, oldOcoId);
            position.setOcoOrderListId(null);

            BigDecimal ocoQty = position.getQuantity()
                    .setScale(2, RoundingMode.DOWN);
            BigDecimal ocoTP  = position.getTakeProfit()
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal ocoSL  = position.getStopLoss()
                    .setScale(2, RoundingMode.DOWN);

            if (ocoTP.compareTo(ocoSL) <= 0) {
                log.warn("⚠️ OCO update skip: TP ${} <= SL ${}", ocoTP, ocoSL);
                return;
            }

            OcoOrderResponse newOco = binanceOcoService.placeOcoOrder(
                    OcoOrderRequest.builder()
                            .base(baseCurrency)
                            .quote(quoteCurrency)
                            .quantity(ocoQty)
                            .takeProfitPrice(ocoTP)
                            .stopLossPrice(ocoSL)
                            .build());

            if ("SUCCESS".equals(newOco.getStatus())) {
                position.setOcoOrderListId(newOco.getOrderListId());
                log.info("✅ OCO updated after trailing: SL=${} id={}",
                        ocoSL, newOco.getOrderListId());
            } else {
                log.warn("⚠️ OCO update failed: {}", newOco.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("❌ OCO update error: {}", e.getMessage());
        }
    }
}
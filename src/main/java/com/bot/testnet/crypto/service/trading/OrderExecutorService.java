package com.bot.testnet.crypto.service.trading;

import com.bot.testnet.crypto.model.LivePosition;
import com.bot.testnet.crypto.model.dto.Signal;
import com.bot.testnet.crypto.model.dto.StrategyType;
import com.bot.testnet.crypto.model.request.GetBalanceCurrencyRequest;
import com.bot.testnet.crypto.model.request.GetCurrentPriceRequest;
import com.bot.testnet.crypto.model.request.PostBuyRequest;
import com.bot.testnet.crypto.model.request.PostSellRequest;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import com.bot.testnet.crypto.service.TelegramNotificationService;
import com.bot.testnet.crypto.service.exchange.BalanceService;
import com.bot.testnet.crypto.service.exchange.BinanceBuyService;
import com.bot.testnet.crypto.service.exchange.BinanceSellService;
import com.bot.testnet.crypto.service.exchange.BinanceService;
import com.bot.testnet.crypto.service.risk.TrailingStopHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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

    @Value("${trading.live.enabled:false}")
    private boolean liveEnabled;

    @Value("${trading.pair.base:BNB}")
    private String baseCurrency;

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
        int hour = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getHour();
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

            // 3. Place market order
            var orderResult = binanceBuyService.placeMarketBuyOrder(
                    buildBuyRequest(quantity));

            // 4. Verify order filled
            if (!"FILLED".equals(orderResult.getStatus())) {
                log.error("❌ [LIVE] Order not filled: {}", orderResult.getStatus());
                telegramService.sendMessage(
                        "❌ [LIVE] Order Failed",
                        "Status: " + orderResult.getStatus() +
                                "\nError: " + orderResult.getErrorMessage());
                return;
            }

            // 5. Create live position
            BigDecimal actualEntry = orderResult.getBalanceAfter() != null
                    ? currentPrice  // use current price as proxy
                    : currentPrice;

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
                        .stopLoss(signal.getStopLoss())
                        .initialStopLoss(signal.getStopLoss())
                        .takeProfit(signal.getTakeProfit())
                        .highestPrice(actualEntry)
                        .trailingActive(false)
                        .status("OPEN")
                        .openTime(Instant.now())
                        .build();

                log.info("✅ [LIVE] Position OPENED #{}: entry=${}, qty={}, SL=${}, TP={}",
                        openPosition.getId(),
                        actualEntry,
                        quantity,
                        signal.getStopLoss(),
                        signal.getTakeProfit() != null ? "$" + signal.getTakeProfit() : "TRAILING");

                sendLivePositionOpenedNotif(openPosition);
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

        // Update trailing SL (EMA strategy only)
        if (lastSnapshot != null
                && openPosition.getStrategy() == StrategyType.EMA_CROSSOVER) {
            trailingStopHelper.update(openPosition, currentPrice, lastSnapshot.getAtr(), "LIVE");
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

    private void closeLivePosition(BigDecimal exitPrice, String reason) {
        positionLock.lock();
        try {
            if (openPosition == null) return;

            log.info("🔄 [LIVE] Closing position #{} ({})...",
                    openPosition.getId(), reason);

            try {
                // Place SELL order
                var sellResult = binanceSellService.placeMarketSellOrder(
                        buildSellRequest(openPosition.getQuantity()));

                if (!"FILLED".equals(sellResult.getStatus())) {
                    log.error("❌ [LIVE] SELL order failed: {}", sellResult.getStatus());
                    telegramService.sendMessage(
                            "🚨 [LIVE] SELL FAILED — MANUAL ACTION NEEDED!",
                            String.format(
                                    "Position #%s could not be closed!\n" +
                                            "Reason: %s\n" +
                                            "Status: %s\n\n" +
                                            "⚠️ Please close manually on Binance!",
                                    openPosition.getId(),
                                    reason,
                                    sellResult.getStatus()));
                    return;
                }

                // Calculate P&L
                BigDecimal pnl = openPosition.calculateUnrealizedPnl(exitPrice);
                boolean isWin = pnl.compareTo(BigDecimal.ZERO) > 0;

                openPosition.setClosePrice(exitPrice);
                openPosition.setRealizedPnl(pnl);
                openPosition.setCloseReason(reason);
                openPosition.setCloseTime(Instant.now());
                openPosition.setStatus("CLOSED");

                // Update stats
                dailyPnl = dailyPnl.add(pnl);
                if (isWin) {
                    consecutiveLosses = 0;
                } else {
                    consecutiveLosses++;
                }
                lastCloseTime = Instant.now();

                log.info("✅ [LIVE] Position CLOSED #{}: {} | P&L: ${}",
                        openPosition.getId(), reason,
                        String.format("%.4f", pnl.doubleValue()));

                closedPositions.add(openPosition);
                sendLivePositionClosedNotif(openPosition);
                openPosition = null;

            } catch (Exception e) {
                log.error("❌ SELL error: {}", e.getMessage());

                // CRITICAL — posisi masih terbuka!
                telegramService.sendMessage(
                        "🚨 CRITICAL — SELL GAGAL!",
                        String.format(
                                "⚠️ POSISI MASIH TERBUKA!\n\n" +
                                        "ID: #%s\n" +
                                        "Pair: BNB/USDT\n" +
                                        "Qty: %s BNB\n" +
                                        "Entry: $%s\n" +
                                        "Error: %s\n\n" +
                                        "🔴 SEGERA TUTUP MANUAL DI BINANCE!\n" +
                                        "⏰ %s WIB",
                                openPosition.getId(),
                                openPosition.getQuantity(),
                                openPosition.getEntryPrice(),
                                e.getMessage(),
                                formatTime()));
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

    private PostBuyRequest buildBuyRequest(BigDecimal quantity) {
        return PostBuyRequest.builder()
                .base(baseCurrency)
                .quote(quoteCurrency)
                .amount(quantity)
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

    private void sendLivePositionClosedNotif(LivePosition position) {
        boolean isWin = position.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0;
        String emoji = isWin ? "✅" : "❌";

        telegramService.sendMessage(
                emoji + " [LIVE] " + position.getCloseReason(),
                String.format(
                        "🆔 #%s | %s\n\n" +
                                "💰 Entry: $%.4f\n" +
                                "💰 Exit:  $%.4f\n" +
                                "%s P&L: <b>$%.4f</b>\n\n" +
                                "📊 Today: Daily P&L $%.4f\n" +
                                "🔁 Consecutive losses: %d\n\n" +
                                "⏰ %s WIB",
                        position.getId(),
                        position.getStrategy(),
                        position.getEntryPrice().doubleValue(),
                        position.getClosePrice().doubleValue(),
                        isWin ? "📈" : "📉",
                        position.getRealizedPnl().doubleValue(),
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
        LocalDate today = LocalDate.now();
        if (lastResetDate == null || !lastResetDate.equals(today)) {
            dailyPnl = BigDecimal.ZERO;
            dailyHalted = false;
            consecutiveLosses = 0;
            lastResetDate = today;
            log.info("📅 [LIVE] Daily stats reset for {}", today);
        }
    }
}
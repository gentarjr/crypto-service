package com.bot.testnet.crypto.service.trading;

import com.bot.testnet.crypto.model.dto.DailyStats;
import com.bot.testnet.crypto.model.dto.Signal;
import com.bot.testnet.crypto.model.dto.SignalAction;
import com.bot.testnet.crypto.model.dto.StrategyType;
import com.bot.testnet.crypto.model.dto.TradeRecord;
import com.bot.testnet.crypto.model.dto.VirtualPosition;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import com.bot.testnet.crypto.service.TelegramNotificationService;
import com.bot.testnet.crypto.service.exchange.BalanceService;
import com.bot.testnet.crypto.service.risk.TrailingStopHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Paper Trading Simulator
 *
 * Simulate trading tanpa eksekusi order real.
 * Track virtual position, P&L, dan statistik.
 *
 * State disimpan in-memory (reset saat restart)
 */
@Service
@RequiredArgsConstructor
@Log4j2
public class PaperTradingService {

    private final TelegramNotificationService telegramService;
    private GetIndicatorResponse lastSnapshot;
    private final TrailingStopHelper trailingStopHelper;
    private final BalanceService balanceService;

    @Value("${trading.risk.max-daily-loss-percent:3.0}")
    private double maxDailyLossPercent;

    @Value("${trading.risk.max-consecutive-losses:3}")
    private int maxConsecutiveLosses;

    private Instant lastCloseTime = null;

    @Value("${trading.risk.cooldown-minutes:30}")
    private int cooldownMinutes;

    // ═══════════════════════════════════════════════════
    // In-Memory State
    // ═══════════════════════════════════════════════════

    private BigDecimal currentCapital;          // modal saat ini
    private VirtualPosition openPosition;       // posisi terbuka (null = tidak ada)
    private DailyStats todayStats;             // statistik hari ini
    private final List<TradeRecord> allTrades = new ArrayList<>();  // semua trade history
    private LocalDate lastResetDate;            // kapan terakhir daily reset

    // ⭐ Lock untuk thread safety
    // PriceMonitorService (WebSocket thread) dan CandleScheduler bisa akses bersamaan
    private final ReentrantLock positionLock = new ReentrantLock();

    // ═══════════════════════════════════════════════════
    // Initialization
    // ═══════════════════════════════════════════════════

    /**
     * Initialize saat pertama kali dipakai
     */
    private void ensureInitialized() {
        if (currentCapital == null) {
            currentCapital = balanceService.getAvailableCapital();
            log.info("💰 Paper trading initialized: Capital ${}", currentCapital);
        }
        checkAndResetDaily();
    }

    /**
     * Reset daily stats kalau hari baru
     */
    private void checkAndResetDaily() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Jakarta"));
        if (lastResetDate == null || !lastResetDate.equals(today)) {
            todayStats = DailyStats.builder()
                    .date(today)
                    .startingCapital(currentCapital)
                    .totalPnl(BigDecimal.ZERO)
                    .totalTrades(0)
                    .wins(0)
                    .losses(0)
                    .consecutiveLosses(0)
                    .trades(new ArrayList<>())
                    .build();
            lastResetDate = today;
            log.info("📅 Daily stats reset for {}", today);
        }
    }

    // ═══════════════════════════════════════════════════
    // Public Methods
    // ═══════════════════════════════════════════════════

    /**
     * Dipanggil dari scheduler tiap closed candle
     *
     * @param signal signal dari AdaptiveSignalService
     * @param currentPrice harga close candle terakhir
     */
    public void onNewCandle(Signal signal, BigDecimal currentPrice) {
        ensureInitialized();

        // Monitor posisi dari candle close (fallback karena WebSocket disabled)
        if (openPosition != null) {
            monitorPositionFromCandle(currentPrice);
        }

        if (todayStats.isHalted()) {
            log.info("🛑 Bot HALTED today. Skip.");
            return;
        }

        if (isDailyLossLimitReached()) {
            haltBot("Daily loss limit reached");
            return;
        }

        if (isConsecutiveLossLimitReached()) {
            haltBot("Max consecutive losses (" + todayStats.getConsecutiveLosses() + "x)");
            return;
        }

        if (openPosition != null) {
            log.debug("Position already open, skip new signal");
            return;
        }

        // ✨ Cooldown check
        if (isCooldownActive()) {
            return;  // Log sudah ada di isCooldownActive()
        }

        if (signal != null && signal.getAction() == SignalAction.BUY) {
            openPosition(signal, currentPrice);
        }
    }

    private void monitorPositionFromCandle(BigDecimal currentPrice) {
        if (openPosition == null) return;

        openPosition.setCurrentPrice(currentPrice);
        openPosition.setUnrealizedPnl(
                openPosition.calculateUnrealizedPnl(currentPrice));

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
                closePositionRealTime(currentPrice, "TIMEOUT_NO_PROGRESS");
                return;
            }
        }

        // ✨ Update trailing SL (hanya EMA strategy)
        if (lastSnapshot != null && openPosition.getStrategy() == StrategyType.EMA_CROSSOVER) {
            trailingStopHelper.update(openPosition, currentPrice, lastSnapshot.getAtr(), "PAPER");
        }

        // Cek TP (hanya untuk BB strategy yang punya fixed TP)
        if (openPosition.isHitTakeProfit(currentPrice)) {
            log.info("🎯 TP HIT: {} >= {}", currentPrice, openPosition.getTakeProfit());
            closePositionRealTime(currentPrice, "TAKE_PROFIT");
            return;
        }

        // Cek SL (bisa SL awal atau trailing SL)
        if (openPosition.isHitStopLoss(currentPrice)) {
            String reason = openPosition.isTrailingActive()
                    ? "TRAILING_STOP"
                    : "STOP_LOSS";
            log.warn("🛑 SL HIT ({}): {} <= {}",
                    reason, currentPrice, openPosition.getStopLoss());
            closePositionRealTime(currentPrice, reason);
        }
    }

    /**
     * ⭐ Close position dari real-time monitor (WebSocket thread)
     * Thread-safe menggunakan lock
     */
    public void closePositionRealTime(BigDecimal exitPrice, String reason) {
        positionLock.lock();
        try {
            if (openPosition == null) {
                log.debug("No open position to close");
                return;
            }
            closePosition(exitPrice, reason);
        } finally {
            positionLock.unlock();
        }
    }

    /**
     * Get daily stats summary untuk Telegram
     */
    public DailyStats getTodayStats() {
        ensureInitialized();
        return todayStats;
    }

    /**
     * Get current capital
     */
    public BigDecimal getCurrentCapital() {
        ensureInitialized();
        return currentCapital;
    }

    /**
     * Get open position (null kalau tidak ada)
     */
    public VirtualPosition getOpenPosition() {
        return openPosition;
    }

    /**
     * Get semua trade history
     */
    public List<TradeRecord> getAllTrades() {
        return new ArrayList<>(allTrades);
    }

    /**
     * Apakah bot sedang halted?
     */
    public boolean isHalted() {
        ensureInitialized();
        return todayStats.isHalted();
    }

    // ═══════════════════════════════════════════════════
    // Private: Position Management
    // ═══════════════════════════════════════════════════

    private void openPosition(Signal signal, BigDecimal currentPrice) {
        positionLock.lock();
        try {
            if (openPosition != null) return;

            if (signal.getStopLoss() == null || signal.getTakeProfit() == null) {
                log.warn("⚠️ Signal missing SL/TP, skip");
                return;
            }

            BigDecimal positionSize = signal.getPositionSize() != null
                    ? signal.getPositionSize().min(currentCapital)
                    : currentCapital.multiply(BigDecimal.valueOf(0.5));

            openPosition = VirtualPosition.builder()
                    .id(java.util.UUID.randomUUID().toString()
                            .substring(0, 8).toUpperCase())
                    .direction(SignalAction.BUY)
                    .strategy(signal.getStrategy())
                    .entryPrice(currentPrice)
                    .positionSize(positionSize)
                    .riskAmount(signal.getRiskAmount())
                    .stopLoss(signal.getStopLoss())
                    .initialStopLoss(signal.getStopLoss())  // ✨ Simpan initial SL
                    .takeProfit(signal.getTakeProfit())
                    .highestPrice(currentPrice)              // ✨ Init highest = entry
                    .trailingActive(false)                   // ✨ Trail belum aktif
                    .openTime(ZonedDateTime.now(ZoneId.of("Asia/Jakarta")).toInstant())
                    .currentPrice(currentPrice)
                    .unrealizedPnl(BigDecimal.ZERO)
                    .build();

            log.info("📂 [PAPER] Position OPENED #{}: entry={}, SL={}, TP={}, size=${}",
                    openPosition.getId(),
                    openPosition.getEntryPrice(),
                    openPosition.getStopLoss(),
                    openPosition.getTakeProfit() != null ? openPosition.getTakeProfit() : "TRAILING",
                    openPosition.getPositionSize());

            sendPositionOpenedNotif(openPosition);
        } finally {
            positionLock.unlock();
        }
    }

    private void closePosition(BigDecimal exitPrice, String reason) {
        if (openPosition == null) return;

        Instant closeTime = ZonedDateTime.now(ZoneId.of("Asia/Jakarta")).toInstant();
        long durationMinutes = Duration.between(openPosition.getOpenTime(), closeTime).toMinutes();

        BigDecimal pnl = openPosition.calculateUnrealizedPnl(exitPrice);
        BigDecimal pnlPercent = pnl.divide(openPosition.getPositionSize(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        currentCapital = currentCapital.add(pnl);
        currentCapital = currentCapital.max(BigDecimal.ZERO);

        TradeRecord record = TradeRecord.builder()
                .id(openPosition.getId())
                .strategy(openPosition.getStrategy())
                .entryPrice(openPosition.getEntryPrice())
                .exitPrice(exitPrice)
                .positionSize(openPosition.getPositionSize())
                .pnl(pnl)
                .pnlPercent(pnlPercent)
                .closeReason(reason)
                .openTime(openPosition.getOpenTime())
                .closeTime(closeTime)
                .durationMinutes(durationMinutes)
                .build();

        todayStats.addTrade(record);
        allTrades.add(record);

        log.info("📁 [PAPER] Position CLOSED #{}: {} | entry={} → exit={} | P&L: ${} ({}%) | {}m",
                record.getId(),
                reason,
                record.getEntryPrice(),
                record.getExitPrice(),
                String.format("%.4f", pnl.doubleValue()),
                String.format("%.2f", pnlPercent.doubleValue()),
                durationMinutes);

        // ✨ Set cooldown timer
        lastCloseTime = closeTime;
        log.info("⏳ Cooldown started: {} minutes", cooldownMinutes);

        sendPositionClosedNotif(record);
        openPosition = null;
    }

    // ═══════════════════════════════════════════════════
    // Private: Risk Checks
    // ═══════════════════════════════════════════════════

    private boolean isDailyLossLimitReached() {
        BigDecimal startingCapital = todayStats.getStartingCapital();
        BigDecimal maxDailyLoss = startingCapital
                .multiply(BigDecimal.valueOf(maxDailyLossPercent / 100));
        BigDecimal currentLoss = todayStats.getTotalPnl();

        // Kalau P&L hari ini negatif dan melebihi max loss
        return currentLoss.compareTo(maxDailyLoss.negate()) < 0;
    }

    private boolean isConsecutiveLossLimitReached() {
        return todayStats.getConsecutiveLosses() >= maxConsecutiveLosses;
    }

    private void haltBot(String reason) {
        log.warn("🛑 BOT HALTED: {}", reason);
        todayStats.setHalted(true);

        // Close posisi terbuka kalau ada
        if (openPosition != null) {
            closePosition(openPosition.getCurrentPrice(), "FORCED_EXIT");
        }

        // Notif Telegram
        telegramService.sendMessage(
                "🛑 Bot HALTED",
                String.format(
                        "Trading stopped today!\n\n" +
                                "📌 Reason: %s\n\n" +
                                "📊 Today's Stats:\n" +
                                "   Trades: %d\n" +
                                "   Win/Loss: %d/%d\n" +
                                "   P&L: $%.2f (%.2f%%)\n\n" +
                                "⏰ Will resume tomorrow.",
                        reason,
                        todayStats.getTotalTrades(),
                        todayStats.getWins(),
                        todayStats.getLosses(),
                        todayStats.getTotalPnl().doubleValue(),
                        todayStats.getTotalPnlPercent().doubleValue()));
    }

    // ═══════════════════════════════════════════════════
    // Private: Notifications
    // ═══════════════════════════════════════════════════

    private void sendPositionOpenedNotif(VirtualPosition position) {
        boolean isEmaStrategy = position.getStrategy() == StrategyType.EMA_CROSSOVER;

        String exitPlanText = isEmaStrategy
                ? String.format("🎯 Exit: <b>TRAILING SL</b> (ATR-based)\n" +
                        "   Aktif setelah profit ≥ 1R ($%.2f)",
                position.getEntryPrice()
                        .subtract(position.getInitialStopLoss()).abs().doubleValue())
                : String.format("🎯 TP: <b>$%.4f</b> (Middle BB)",
                position.getTakeProfit() != null
                        ? position.getTakeProfit().doubleValue() : 0);

        telegramService.sendMessage(
                "📂 [PAPER] Position Opened",
                String.format(
                        "🆔 #%s | %s\n\n" +
                                "💰 Entry: <b>$%.4f</b>\n" +
                                "🛑 SL:    <b>$%.4f</b>\n" +
                                "%s\n\n" +
                                "📊 Size:  <b>$%.2f</b>\n" +
                                "💼 Capital: $%.2f\n" +
                                "⏰ %s WIB",
                        position.getId(),
                        position.getStrategy(),
                        position.getEntryPrice().doubleValue(),
                        position.getStopLoss().doubleValue(),
                        exitPlanText,
                        position.getPositionSize().doubleValue(),
                        currentCapital.doubleValue(),
                        formatTime()));
    }

    private void sendPositionClosedNotif(TradeRecord record) {
        boolean isWin = record.isWin();
        String emoji = isWin ? "✅" : "❌";
        String title = String.format("%s [PAPER] Position Closed — %s",
                emoji, record.getCloseReason());

        String msg = String.format(
                "🆔 ID: #%s\n" +
                        "📋 Strategy: %s\n\n" +
                        "💰 Entry: $%.4f\n" +
                        "💰 Exit:  $%.4f\n\n" +
                        "%s P&L: <b>$%.4f (%.2f%%)</b>\n" +
                        "⏱ Duration: %d minutes\n\n" +
                        "📊 Today:\n" +
                        "   Trades: %d | W/L: %d/%d\n" +
                        "   P&L today: $%.2f (%.2f%%)\n" +
                        "   Consecutive Loss: %d\n\n" +
                        "💼 Capital: <b>$%.2f</b>\n" +
                        "⏰ %s WIB",
                record.getId(),
                record.getStrategy(),
                record.getEntryPrice().doubleValue(),
                record.getExitPrice().doubleValue(),
                isWin ? "📈" : "📉",
                record.getPnl().doubleValue(),
                record.getPnlPercent().doubleValue(),
                record.getDurationMinutes(),
                todayStats.getTotalTrades(),
                todayStats.getWins(),
                todayStats.getLosses(),
                todayStats.getTotalPnl().doubleValue(),
                todayStats.getTotalPnlPercent().doubleValue(),
                todayStats.getConsecutiveLosses(),
                currentCapital.doubleValue(),
                formatTime());

        telegramService.sendMessage(title, msg);
    }

    private String formatTime() {
        return LocalDateTime.now(ZoneId.of("Asia/Jakarta"))
                .format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"));
    }

    /**
     * Apakah masih dalam cooldown period setelah last trade close?
     */
    private boolean isCooldownActive() {
        if (lastCloseTime == null) return false;
        Instant cooldownEnd = lastCloseTime.plus(
                java.time.Duration.ofMinutes(cooldownMinutes));
        boolean active = Instant.now().isBefore(cooldownEnd);

        if (active) {
            long remainingSeconds = java.time.Duration.between(
                    Instant.now(), cooldownEnd).getSeconds();
            log.info("⏳ Cooldown active — {}m {}s remaining",
                    remainingSeconds / 60, remainingSeconds % 60);
        }
        return active;
    }

    public void updateSnapshot(GetIndicatorResponse snapshot) {
        this.lastSnapshot = snapshot;
    }
}
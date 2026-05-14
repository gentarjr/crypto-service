package com.bot.testnet.crypto.service.scheduler;

import com.bot.testnet.crypto.model.dto.DailyStats;
import com.bot.testnet.crypto.service.TelegramNotificationService;
import com.bot.testnet.crypto.service.trading.PaperTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Kirim daily summary ke Telegram setiap jam 23:59
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class DailySummaryScheduler {

    private final PaperTradingService paperTradingService;
    private final TelegramNotificationService telegramService;

    /**
     * Daily summary setiap jam 23:59:00
     */
    @Scheduled(cron = "0 59 23 * * *")
    public void sendDailySummary() {
        log.info("📊 Sending daily summary...");

        DailyStats stats = paperTradingService.getTodayStats();

        if (stats == null || stats.getTotalTrades() == 0) {
            log.info("No trades today, skip summary");
            return;
        }

        String title = "📊 Daily Summary — " +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));

        StringBuilder msg = new StringBuilder();
        msg.append(String.format("💰 Capital: <b>$%.2f</b> / $%.2f\n",
                paperTradingService.getCurrentCapital().doubleValue(),
                stats.getStartingCapital().doubleValue()));

        String pnlEmoji = stats.getTotalPnl().doubleValue() >= 0 ? "📈" : "📉";
        msg.append(String.format("%s P&L: <b>$%.2f (%.2f%%)</b>\n\n",
                pnlEmoji,
                stats.getTotalPnl().doubleValue(),
                stats.getTotalPnlPercent().doubleValue()));

        msg.append(String.format("📋 Trades: %d\n", stats.getTotalTrades()));
        msg.append(String.format("✅ Win:  %d\n", stats.getWins()));
        msg.append(String.format("❌ Loss: %d\n", stats.getLosses()));
        msg.append(String.format("🎯 Win Rate: %.1f%%\n\n", stats.getWinRate()));

        // Trade list
        if (stats.getTrades() != null && !stats.getTrades().isEmpty()) {
            msg.append("<b>Trade Details:</b>\n");
            stats.getTrades().forEach(t -> {
                String tEmoji = t.isWin() ? "✅" : "❌";
                msg.append(String.format("%s #%s | %s | $%.2f (%.2f%%) | %dm\n",
                        tEmoji,
                        t.getId(),
                        t.getStrategy(),
                        t.getPnl().doubleValue(),
                        t.getPnlPercent().doubleValue(),
                        t.getDurationMinutes()));
            });
        }

        msg.append(String.format("\n🤖 Status: %s",
                stats.isHalted() ? "HALTED 🛑" : "ACTIVE ✅"));

        telegramService.sendMessage(title, msg.toString());
        log.info("✅ Daily summary sent");
    }

    /**
     * Hourly mini-update (opsional, bisa di-disable)
     * Setiap jam di menit ke-0
     */
    @Scheduled(cron = "0 0 * * * *")
    public void sendHourlyUpdate() {
        DailyStats stats = paperTradingService.getTodayStats();

        if (stats == null || stats.getTotalTrades() == 0) {
            return;  // Skip kalau belum ada trade hari ini
        }

        log.info("⏰ Hourly update: trades={}, P&L={}",
                stats.getTotalTrades(), stats.getTotalPnl());

        // Hanya log, tidak kirim Telegram untuk avoid spam
        // Uncomment kalau mau kirim:

        telegramService.sendMessage(
                "⏰ Hourly Update",
                String.format("Trades: %d | P&L: $%.2f | Capital: $%.2f",
                        stats.getTotalTrades(),
                        stats.getTotalPnl().doubleValue(),
                        paperTradingService.getCurrentCapital().doubleValue()));

    }
}
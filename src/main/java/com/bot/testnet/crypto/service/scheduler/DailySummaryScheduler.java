package com.bot.testnet.crypto.service.scheduler;

import com.bot.testnet.crypto.model.dto.DailyStats;
import com.bot.testnet.crypto.service.TelegramNotificationService;
import com.bot.testnet.crypto.service.trading.OrderExecutorService;
import com.bot.testnet.crypto.service.trading.PaperTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private final OrderExecutorService orderExecutorService;

    @Value("${trading.paper.enabled:true}")
    private boolean paperEnabled;

    /**
     * Daily summary setiap jam 23:59:00
     */
    @Scheduled(cron = "0 59 23 * * *", zone = "Asia/Jakarta")  // ✅ Tambah zone WIB
    public void sendDailySummary() {
        log.info("📊 Sending daily summary...");

        if(paperEnabled) {
            DailyStats stats = paperTradingService.getTodayStats();

            String title = "📊 Daily Summary — " +
                    LocalDateTime.now(ZoneId.of("Asia/Jakarta"))
                            .format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));

            StringBuilder msg = new StringBuilder();

            if (stats == null || stats.getTotalTrades() == 0) {
                msg.append("📭 No trades today\n\n");
                msg.append(String.format("💰 Capital: <b>$%.2f</b>\n",
                        paperTradingService.getCurrentCapital().doubleValue()));
                msg.append("🤖 Bot is monitoring the market...");
                telegramService.sendMessage(title, msg.toString());
                log.info("✅ Daily summary sent (no trades)");
                return;
            }

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

            if (stats.getTrades() != null && !stats.getTrades().isEmpty()) {
                msg.append("<b>Trade Details:</b>\n");
                stats.getTrades().forEach(t -> {
                    String tEmoji = t.isWin() ? "✅" : "❌";
                    msg.append(String.format("%s #%s | %s | $%.4f (%.2f%%) | %dm\n",
                            tEmoji, t.getId(), t.getStrategy(),
                            t.getPnl().doubleValue(),
                            t.getPnlPercent().doubleValue(),
                            t.getDurationMinutes()));
                });
            }

            msg.append(String.format("\n🤖 Status: %s\n",
                    stats.isHalted() ? "HALTED 🛑" : "ACTIVE ✅"));
            msg.append(String.format("⏰ %s WIB", formatTime()));

            telegramService.sendMessage(title, msg.toString());
            log.info("✅ Daily summary sent");
        }else{
            String title = "📊 [LIVE] Daily Summary — " +
                    LocalDateTime.now(ZoneId.of("Asia/Jakarta"))
                            .format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));

            StringBuilder msg = new StringBuilder();
            msg.append(String.format("📋 Total Trades: <b>%d</b>\n",
                    orderExecutorService.getClosedCount()));
            msg.append(String.format("🔄 Consecutive Losses: <b>%d</b>\n",
                    orderExecutorService.getConsecutiveLosses()));
            msg.append(String.format("🤖 Status: <b>%s</b>\n",
                    orderExecutorService.isHalted() ? "HALTED 🛑" : "ACTIVE ✅"));
            msg.append(String.format("⏰ %s WIB", formatTime()));

            telegramService.sendMessage(title, msg.toString());
            log.info("✅ Live daily summary sent");
        }
    }

    // ✅ Fix hourly — hanya log, TIDAK kirim Telegram
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Jakarta")
    public void sendHourlyUpdate() {
        DailyStats stats = paperTradingService.getTodayStats();
        if (stats == null || stats.getTotalTrades() == 0) return;

        // Hanya log, tidak kirim Telegram
        log.info("⏰ Hourly: trades={}, P&L=${}, capital=${}",
                stats.getTotalTrades(),
                stats.getTotalPnl(),
                paperTradingService.getCurrentCapital());
    }

    private String formatTime() {
        return LocalDateTime.now(ZoneId.of("Asia/Jakarta"))
                .format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"));
    }
}
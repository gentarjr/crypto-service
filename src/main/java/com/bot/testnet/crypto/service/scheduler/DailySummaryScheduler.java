package com.bot.testnet.crypto.service.scheduler;

import com.bot.testnet.crypto.model.dto.DailyStats;
import com.bot.testnet.crypto.repository.TradeHistoryRepository;
import com.bot.testnet.crypto.service.TelegramNotificationService;
import com.bot.testnet.crypto.service.trading.PaperTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
@Log4j2
public class DailySummaryScheduler {

    private final PaperTradingService paperTradingService;
    private final TelegramNotificationService telegramService;
    private final TradeHistoryRepository tradeHistoryRepository;

    @Value("${trading.paper.enabled:true}")
    private boolean paperEnabled;

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Jakarta")
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
        } else {
            Instant startOfDayUtc = LocalDate.now(ZoneOffset.UTC)
                    .atStartOfDay(ZoneOffset.UTC).toInstant();

            String title = "📊 [LIVE] Daily Summary — " +
                    LocalDateTime.now(ZoneId.of("Asia/Jakarta"))
                            .format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));

            StringBuilder msg = new StringBuilder();

            appendPairSummary(msg, "🟡 BNB/USDT", "BNB", startOfDayUtc);
            msg.append("\n");
            appendPairSummary(msg, "🔷 ETH/USDC", "ETH", startOfDayUtc);

            msg.append(String.format("\n⏰ %s WIB", formatTime()));

            telegramService.sendMessage(title, msg.toString());
            log.info("✅ Live daily summary sent (consolidated BNB+ETH)");
        }
    }

    private void appendPairSummary(StringBuilder msg, String label, String pair, Instant since) {
        long totalToday = tradeHistoryRepository.countTotalSinceByPair(since, pair);
        long winsToday = tradeHistoryRepository.countWinsSinceByPair(since, pair);
        BigDecimal pnlToday = tradeHistoryRepository.sumPnlSinceByPair(since, pair);
        if (pnlToday == null) pnlToday = BigDecimal.ZERO;

        msg.append(String.format("<b>%s</b>\n", label));

        if (totalToday == 0) {
            msg.append("📭 Belum ada trade hari ini\n");
            return;
        }

        long lossToday = totalToday - winsToday;
        double winRate = totalToday > 0 ? (winsToday * 100.0 / totalToday) : 0;
        String pnlEmoji = pnlToday.doubleValue() >= 0 ? "📈" : "📉";

        msg.append(String.format("📋 Trades: <b>%d</b> (✅%d / ❌%d)\n", totalToday, winsToday, lossToday));
        msg.append(String.format("🎯 Win Rate: <b>%.1f%%</b>\n", winRate));
        msg.append(String.format("%s P&L: <b>$%.4f</b>\n", pnlEmoji, pnlToday.doubleValue()));
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
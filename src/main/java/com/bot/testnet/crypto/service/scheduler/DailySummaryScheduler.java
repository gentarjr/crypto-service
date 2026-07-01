package com.bot.testnet.crypto.service.scheduler;

import com.bot.testnet.crypto.repository.TradeHistoryRepository;
import com.bot.testnet.crypto.service.TelegramNotificationService;
import com.bot.testnet.crypto.service.exchange.BalanceService;
import com.bot.testnet.crypto.service.exchange.BalanceServiceEth;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Log4j2
public class DailySummaryScheduler {

    private final TelegramNotificationService telegramService;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final BalanceService balanceService;
    private final BalanceServiceEth balanceServiceEth;

    // ── 13.00 WIB — Bot mulai, greeting ───────────────────────────
    @Scheduled(cron = "0 0 13 * * *", zone = "Asia/Jakarta")
    public void sendMorningGreeting() {
        log.info("☀️ Sending morning greeting...");
        try {
            BigDecimal bnbBalance = balanceService.getTotalCapitalSafe().orElse(BigDecimal.ZERO);
            BigDecimal ethBalance = balanceServiceEth.getTotalCapitalSafe().orElse(BigDecimal.ZERO);
            BigDecimal totalBalance = bnbBalance.add(ethBalance);

            String date = LocalDateTime.now(ZoneId.of("Asia/Jakarta"))
                    .format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy", new Locale("id", "ID")));

            String msg = String.format(
                    "🌤 Selamat siang! Hari ini <b>%s</b>\n\n" +
                            "🤖 Bot sudah mulai trading!\n\n" +
                            "💼 <b>Portfolio Saat Ini:</b>\n" +
                            "   🟡 BNB/USDT : <b>$%.2f</b>\n" +
                            "   🔷 ETH/USDC : <b>$%.2f</b>\n" +
                            "   📊 Total     : <b>$%.2f</b>\n\n" +
                            "⏱ Sesi trading: 13.00 — 07.00 WIB\n" +
                            "⏰ %s WIB",
                    date, bnbBalance, ethBalance, totalBalance, formatTime());

            telegramService.sendMessage("☀️ Selamat Siang — Bot Aktif", msg);
            log.info("✅ Morning greeting sent");
        } catch (Exception e) {
            telegramService.sendMessage("❌ Morning Greeting Error", "Gagal kirim: " + e.getMessage());
        }
    }

    // ── 07.00 WIB — Penutupan, summary sesi kemarin ───────────────
    // Range query: 13.00 WIB kemarin → 07.00 WIB sekarang (1 sesi penuh)
    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Jakarta")
    public void sendClosingSummary() {
        log.info("🌙 Sending closing summary...");
        try {
            Instant sessionStart = LocalDate.now(ZoneId.of("Asia/Jakarta"))
                    .minusDays(1)
                    .atTime(13, 0)
                    .atZone(ZoneId.of("Asia/Jakarta"))
                    .toInstant();

            String dateFrom = LocalDate.now(ZoneId.of("Asia/Jakarta"))
                    .minusDays(1).format(DateTimeFormatter.ofPattern("dd-MM"));
            String dateTo = LocalDate.now(ZoneId.of("Asia/Jakarta"))
                    .format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));

            StringBuilder msg = new StringBuilder();
            msg.append(String.format("📅 Sesi: <b>%s 13.00</b> → <b>%s 07.00</b> WIB\n\n", dateFrom, dateTo));

            appendPairSection(msg, "🟡 BNB/USDT", "BNB", sessionStart);
            msg.append("\n");
            appendPairSection(msg, "🔷 ETH/USDC", "ETH", sessionStart);

            BigDecimal totalPnl = safeAdd(
                    tradeHistoryRepository.sumPnlSinceByPair(sessionStart, "BNB"),
                    tradeHistoryRepository.sumPnlSinceByPair(sessionStart, "ETH"));
            String totalEmoji = totalPnl.compareTo(BigDecimal.ZERO) >= 0 ? "📈" : "📉";
            msg.append(String.format("\n%s <b>Total P&L Gabungan: $%.4f</b>", totalEmoji, totalPnl));
            msg.append(String.format("\n⏰ %s WIB", formatTime()));

            telegramService.sendMessage("🌙 Closing Summary — Sesi Berakhir", msg.toString());
            log.info("✅ Closing summary sent");
        } catch (Exception e) {
            telegramService.sendMessage("❌ Closing Summary Error", "Gagal kirim: " + e.getMessage());
        }
    }

    // ── Jumat 17.00 WIB — Weekly report ───────────────────────────
    @Scheduled(cron = "0 0 17 * * FRI", zone = "Asia/Jakarta")
    public void sendWeeklyReport() {
        log.info("📈 Sending weekly report...");
        try {
            Instant weekStart = Instant.now().minus(7, ChronoUnit.DAYS);

            String dateFrom = LocalDateTime.ofInstant(weekStart, ZoneId.of("Asia/Jakarta"))
                    .format(DateTimeFormatter.ofPattern("dd MMM"));
            String dateTo = LocalDateTime.now(ZoneId.of("Asia/Jakarta"))
                    .format(DateTimeFormatter.ofPattern("dd MMM yyyy"));

            StringBuilder msg = new StringBuilder();
            msg.append(String.format("📅 Periode: <b>%s — %s</b>\n\n", dateFrom, dateTo));

            appendPairSection(msg, "🟡 BNB/USDT", "BNB", weekStart);
            msg.append("\n");
            appendPairSection(msg, "🔷 ETH/USDC", "ETH", weekStart);

            BigDecimal totalPnl = safeAdd(
                    tradeHistoryRepository.sumPnlSinceByPair(weekStart, "BNB"),
                    tradeHistoryRepository.sumPnlSinceByPair(weekStart, "ETH"));
            long totalTrades =
                    tradeHistoryRepository.countTotalSinceByPair(weekStart, "BNB") +
                            tradeHistoryRepository.countTotalSinceByPair(weekStart, "ETH");
            String totalEmoji = totalPnl.compareTo(BigDecimal.ZERO) >= 0 ? "📈" : "📉";

            msg.append(String.format("\n📊 <b>TOTAL GABUNGAN</b>\n"));
            msg.append(String.format("   Trades : <b>%d</b>\n", totalTrades));
            msg.append(String.format("   %s P&L  : <b>$%.4f</b>", totalEmoji, totalPnl));
            msg.append(String.format("\n⏰ %s WIB", formatTime()));

            telegramService.sendMessage("📈 Weekly Report", msg.toString());
            log.info("✅ Weekly report sent");
        } catch (Exception e) {
            telegramService.sendMessage("❌ Weekly Report Error", "Gagal kirim: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    private void appendPairSection(StringBuilder msg, String label, String pair, Instant since) {
        long total  = tradeHistoryRepository.countTotalSinceByPair(since, pair);
        long wins   = tradeHistoryRepository.countWinsSinceByPair(since, pair);
        long sl     = tradeHistoryRepository.countSlSinceByPair(since, pair);
        long tp     = tradeHistoryRepository.countTpSinceByPair(since, pair);
        BigDecimal pnl     = safeAdd(tradeHistoryRepository.sumPnlSinceByPair(since, pair), null);
        BigDecimal winPnl  = safeAdd(tradeHistoryRepository.sumWinPnlSinceByPair(since, pair), null);
        BigDecimal lossPnl = safeAdd(tradeHistoryRepository.sumLossPnlSinceByPair(since, pair), null);
        Double avgDur = tradeHistoryRepository.avgDurationSinceByPair(since, pair);

        msg.append(String.format("<b>%s</b>\n", label));
        if (total == 0) {
            msg.append("📭 Tidak ada trade\n");
            return;
        }

        long losses = total - wins;
        double winRate = (wins * 100.0 / total);
        String pnlEmoji = pnl.compareTo(BigDecimal.ZERO) >= 0 ? "📈" : "📉";

        msg.append(String.format("📋 Trades  : <b>%d</b> (✅%d / ❌%d)\n", total, wins, losses));
        msg.append(String.format("🎯 Win Rate: <b>%.1f%%</b>\n", winRate));
        msg.append(String.format("🔴 SL/Trail: <b>%d</b>\n", sl));
        msg.append(String.format("🟢 TP      : <b>%d</b>\n", tp));
        msg.append(String.format("%s P&L     : <b>$%.4f</b>\n", pnlEmoji, pnl));
        msg.append(String.format("   Win    : <b>$%.4f</b>\n", winPnl));
        msg.append(String.format("   Loss   : <b>$%.4f</b>\n", lossPnl));
        if (avgDur != null) {
            msg.append(String.format("⏱ Avg Dur : <b>%.0f menit</b>\n", avgDur));
        }
    }

    private BigDecimal safeAdd(BigDecimal a, BigDecimal b) {
        BigDecimal r = a != null ? a : BigDecimal.ZERO;
        return b != null ? r.add(b) : r;
    }

    private String formatTime() {
        return LocalDateTime.now(ZoneId.of("Asia/Jakarta"))
                .format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"));
    }
}
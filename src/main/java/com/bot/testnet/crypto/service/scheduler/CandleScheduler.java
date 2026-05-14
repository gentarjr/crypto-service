package com.bot.testnet.crypto.service.scheduler;

import com.bot.testnet.crypto.model.dto.*;
import com.bot.testnet.crypto.model.request.GetCandleRequest;
import com.bot.testnet.crypto.model.response.GetCandleResponse;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import com.bot.testnet.crypto.service.TelegramNotificationService;
import com.bot.testnet.crypto.service.exchange.AdaptiveSignalService;
import com.bot.testnet.crypto.service.exchange.BalanceService;
import com.bot.testnet.crypto.service.exchange.CandleCache;
import com.bot.testnet.crypto.service.exchange.CandleService;
import com.bot.testnet.crypto.service.indicator.IndicatorService;
import com.bot.testnet.crypto.service.risk.TradingHoursService;
import com.bot.testnet.crypto.service.trading.OrderExecutorService;
import com.bot.testnet.crypto.service.trading.PaperTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.knowm.xchange.binance.dto.marketdata.KlineInterval;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
import java.time.ZoneId;

@Component
@RequiredArgsConstructor
@Slf4j
public class CandleScheduler {

    private final CandleService candleService;
    private final CandleCache candleCache;
    private final IndicatorService indicatorService;
    private final AdaptiveSignalService adaptiveSignalService;
    private final TelegramNotificationService telegramService;
    private final PaperTradingService paperTradingService;
    private final TradingHoursService tradingHoursService;
    private final OrderExecutorService orderExecutorService;
    private final BalanceService balanceService;

    private String lastRegime = StringUtils.EMPTY;
    private int candleFetchErrorCount = 0;
    private static final int MAX_ERROR_BEFORE_ALERT = 3;

    @Value("${trading.pair.base}")
    private String baseCurrency;

    @Value("${trading.pair.quote}")
    private String quoteCurrency;

    @Value("${trading.market-data.primary-interval}")
    private String primaryInterval;

    @Value("${trading.market-data.cache-size}")
    private int cacheSize;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("🚀 Bot fully started!");
        telegramService.sendMessage(
                "🤖 Bot Started",
                String.format(
                        "✅ Crypto Bot ONLINE\n\n" +
                                "📋 Config:\n" +
                                "   Pair: <b>BNB/USDT</b>\n" +
                                "   Timeframe: <b>m15</b>\n" +
                                "   Live Trading: <b>%s</b>\n" +
                                "   Paper Trading: <b>Active</b>\n\n" +
                                "💰 Balance akan di-fetch saat signal pertama\n\n" +
                                "⏰ %s WIB",
                        orderExecutorService.isEnabled() ? "ENABLED ✅" : "DISABLED ❌",
                        formatTime()));
    }

    /**
     * Initialize cache saat aplikasi startup
     * Fetch 200 candle awal sebagai context
     */
    @PostConstruct
    public void initialize() {
        try {
            log.info("🚀 Initializing candle cache...");
            GetCandleResponse initialCandles = candleService.fetchCandles(
                    GetCandleRequest.builder()
                            .base(baseCurrency)
                            .quote(quoteCurrency)
                            .interval(KlineInterval.valueOf(primaryInterval))
                            .limit(cacheSize)
                            .build());
            candleCache.initialize(initialCandles.getCandle());
        } catch (Exception e) {
            log.error("❌ Failed to initialize candle cache", e);
        }
    }

    /**
     * Auto-fetch candle baru tiap interval
     * fixedRate = milliseconds
     *
     * 60_000 = 60 detik = 1 menit
     * initialDelay = 10_000 = tunggu 10 detik setelah startup
     */
    @Scheduled(fixedRate = 60000, initialDelay = 10000)
    public void fetchLatestCandle() {
        try {
            GetCandleResponse latestCandles = candleService.fetchCandles(
                    GetCandleRequest.builder()
                            .base(baseCurrency)
                            .quote(quoteCurrency)
                            .interval(KlineInterval.valueOf(primaryInterval))
                            .limit(2)
                            .build());

            Candle lastCached = candleCache.getLastCandle();
            boolean hasNewClosedCandle = false;

            for (Candle candle : latestCandles.getCandle()) {
                // Skip candle yang lebih lama dari yang sudah di cache
                if (lastCached != null
                        && candle.getOpenTime().isBefore(lastCached.getOpenTime())) {
                    continue;
                }

                // ✨ NEW: Gunakan enum result untuk decide trigger
                CandleUpdateResult result = candleCache.addOrUpdateCandle(candle);

                if (result.shouldTriggerIndicators()) {
                    hasNewClosedCandle = true;
                    log.debug("Should trigger indicators because: {}", result);
                }
            }

            if (hasNewClosedCandle) {
                onNewClosedCandle();
            }
            candleFetchErrorCount = 0;
            log.debug("⏰ Cache size: {} candles", candleCache.size());
        } catch (Exception e) {
            log.error("❌ Failed to fetch latest candle", e);
            candleFetchErrorCount++;
            log.error("❌ Failed to fetch candle: {}", e.getMessage());

            // Kirim alert kalau error 3x berturut-turut
            if (candleFetchErrorCount >= MAX_ERROR_BEFORE_ALERT) {
                telegramService.sendMessage(
                        "⚠️ Candle Fetch Error",
                        String.format(
                                "Gagal fetch candle %d kali berturut-turut!\n\n" +
                                        "Error: %s\n\n" +
                                        "Bot masih running tapi data mungkin stale.\n" +
                                        "⏰ %s WIB",
                                candleFetchErrorCount,
                                e.getMessage(),
                                formatTime()));
                candleFetchErrorCount = 0; // reset setelah alert
            }
        }
    }

    @Scheduled(cron = "0 0 1 * * *")
    public void sendMorningHealthCheck() {
        try {
            BigDecimal balance = balanceService.getAvailableCapital();
            DailyStats stats = paperTradingService.getTodayStats();
            boolean liveHalted = orderExecutorService.isHalted();
            boolean paperHalted = paperTradingService.isHalted();

            String status = (liveHalted || paperHalted) ? "⚠️ HALTED" : "✅ Running";

            telegramService.sendMessage(
                    "☀️ Morning Health Check",
                    String.format(
                            "Status: <b>%s</b>\n\n" +
                                    "💰 Balance: <b>$%.2f USDT</b>\n" +
                                    "📊 Paper Capital: <b>$%.2f</b>\n\n" +
                                    "Yesterday:\n" +
                                    "   Trades: %d\n" +
                                    "   P&L: $%.4f\n\n" +
                                    "Bot siap trading hari ini!\n" +
                                    "⏰ %s WIB",
                            status,
                            balance.doubleValue(),
                            paperTradingService.getCurrentCapital().doubleValue(),
                            stats.getTotalTrades(),
                            stats.getTotalPnl().doubleValue(),
                            formatTime()));

        } catch (Exception e) {
            telegramService.sendMessage(
                    "❌ Health Check Error",
                    "Gagal kirim morning report!\n" +
                            "Error: " + e.getMessage());
        }
    }

    /**
     * Trigger event: candle baru tutup
     * Nanti di sini kita panggil indikator & signal generator
     */
    private void onNewClosedCandle() {
        try {
            Candle latestClosed = candleCache.getLastClosedCandle();
            log.info("🎯 NEW CLOSED CANDLE detected!");
            log.info("   Time: {}", latestClosed.getCloseTime());
            log.info("   OHLC: O={} H={} L={} C={}",
                    latestClosed.getOpen(),
                    latestClosed.getHigh(),
                    latestClosed.getLow(),
                    latestClosed.getClose());
            log.info("   Volume: {}", latestClosed.getVolume());
            log.info("   Type: {}", latestClosed.isBullish() ? "🟢 BULLISH" : "🔴 BEARISH");

            // ✨ NEW: Hitung indikator setelah candle baru tutup
            log.info("🧮 Calculating indicators...");
            GetIndicatorResponse snapshot = indicatorService.calculate();

            if (snapshot == null) {
                log.warn("⚠️ Insufficient data for indicators");
                telegramService.sendMessage(
                        "⚠️ Indicator Error",
                        "Tidak bisa hitung indikator!\n" +
                                "Kemungkinan data candle tidak cukup.\n" +
                                "⏰ " + formatTime() + " WIB");
                return;
            }

            paperTradingService.updateSnapshot(snapshot);
            orderExecutorService.updateSnapshot(snapshot);

            log.info("📋 Regime: {} → Strategy: {}",
                    snapshot.getMarketRegime(),
                    snapshot.getPreferredStrategy());

            if (!tradingHoursService.isWithinTradingHours()) {
                log.info("🕐 Outside trading hours — skip signal evaluation");
                return;
            }

            // Step 2: Evaluate signal (delegate ke AdaptiveSignalService)
            Signal signal = adaptiveSignalService.evaluate(snapshot);
            // Step 3: Log signal
            logSignal(signal);
            // Step 4: Send notif hanya kalau signal BARU & actionable
            boolean isNew = adaptiveSignalService.isNewActionableSignal(signal);
            if (isNew) {
                sendSignalNotification(signal);
            } else {
                sendHoldNotificationIfRegimeChanged(signal, snapshot);
            }

            BigDecimal currentPrice = snapshot.getCurrentPrice();
            paperTradingService.onNewCandle(signal, currentPrice);

            if (orderExecutorService.isEnabled()) {
                orderExecutorService.onNewCandle(signal, currentPrice, snapshot);
            }
            // Step 6: Log position status
            logPositionStatus(currentPrice, snapshot);

            log.info("════════════════════════════════════════");
        }catch (Exception e){
            log.error("❌ Error in onNewClosedCandle: {}", e.getMessage());
            telegramService.sendMessage(
                    "❌ Bot Error",
                    String.format(
                            "Error saat proses candle!\n\n" +
                                    "Error: %s\n\n" +
                                    "⏰ %s WIB",
                            e.getMessage(),
                            formatTime()));
        }
    }

    private void logPositionStatus(BigDecimal currentPrice, GetIndicatorResponse snapshot) {
        // Paper position
        var paperPos = paperTradingService.getOpenPosition();
        if (paperPos != null) {
            BigDecimal unrealized = paperPos.calculateUnrealizedPnl(currentPrice);
            log.info("📄 Paper #{}: entry=${} | now=${} | unrealized=${} | trailing={}",
                    paperPos.getId(),
                    paperPos.getEntryPrice(),
                    currentPrice,
                    String.format("%.4f", unrealized.doubleValue()),
                    paperPos.isTrailingActive() ? "ACTIVE (SL=$" + paperPos.getStopLoss() + ")" : "inactive");
        }

        // Live position
        var livePos = orderExecutorService.getOpenPosition();
        if (livePos != null) {
            BigDecimal unrealized = livePos.calculateUnrealizedPnl(currentPrice);
            log.info("💰 Live #{}: entry=${} | now=${} | unrealized=${} | trailing={}",
                    livePos.getId(),
                    livePos.getEntryPrice(),
                    currentPrice,
                    String.format("%.4f", unrealized.doubleValue()),
                    livePos.isTrailingActive() ? "ACTIVE (SL=$" + livePos.getStopLoss() + ")" : "inactive");
        }
    }

    private void logSignal(Signal signal) {
        switch (signal.getAction()) {
            case BUY -> {
                log.info("🟢 BUY Signal!");
                log.info("   {}", signal.getSummary());
                log.info("   Filters passed: {}/{}",
                        signal.getPassedFilterCount(),
                        signal.getFilters().size());
            }
            case SELL -> {
                log.info("🔴 SELL Signal!");
                log.info("   {}", signal.getSummary());
            }
            case HOLD -> {
                // Log filter yang fail (kenapa hold)
                if (signal.getFilters() != null && !signal.getFilters().isEmpty()) {
                    signal.getFilters().stream()
                            .filter(f -> !f.isPass())
                            .forEach(f -> log.info("   ⏸️ HOLD — [{}] {}",
                                    f.getFilterName(), f.getReason()));
                } else {
                    log.info("   ⏸️ HOLD — {}", signal.getSummary());
                }
            }
        }
    }

    private void sendSignalNotification(Signal signal) {
        String title = signal.getAction() == SignalAction.BUY
                ? "🟢 BUY Signal — " + signal.getStrategy()
                : "🔴 SELL Signal — " + signal.getStrategy();

        StringBuilder msg = new StringBuilder();
        msg.append(String.format("💰 Price: <b>%.4f</b> USDT\n\n", signal.getPrice().doubleValue()));

        if (signal.getStopLoss() != null) {
            msg.append(String.format("🛑 Stop Loss:   <b>%.4f</b>\n", signal.getStopLoss().doubleValue()));
        }
        if (signal.getTakeProfit() != null) {
            msg.append(String.format("🎯 Take Profit: <b>%.4f</b>\n", signal.getTakeProfit().doubleValue()));
        }
        if (signal.getPositionSize() != null) {
            msg.append(String.format("📊 Position:    <b>$%.2f USDT</b>\n", signal.getPositionSize().doubleValue()));
        }
        if (signal.getRiskAmount() != null) {
            msg.append(String.format("⚡ Risk:        <b>$%.2f (1%%)</b>\n", signal.getRiskAmount().doubleValue()));
        }
        if (signal.getRiskRewardRatio() != null) {
            msg.append(String.format("📈 R:R Ratio:   <b>1:%.2f</b>\n", signal.getRiskRewardRatio().doubleValue()));
        }

        // Filter summary
        msg.append("\n<b>✅ All filters passed:</b>\n");
        if (signal.getFilters() != null) {
            signal.getFilters().forEach(f ->
                    msg.append(String.format("  • %s\n", f.getReason())));
        }

        msg.append(String.format("\n⏰ %s WIB",
                java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"))));

        telegramService.sendMessage(title, msg.toString());
    }

    private void sendHoldNotificationIfRegimeChanged(Signal signal,
                                                     GetIndicatorResponse snapshot) {
        String currentRegime = snapshot.getMarketRegime();

        // Skip kalau regime sama dengan sebelumnya
        if (currentRegime.equals(lastRegime)) {
            log.debug("Regime sama ({}), skip HOLD notif", currentRegime);
            return;
        }

        // Regime berubah! Update dan kirim notif
        log.info("📊 Regime changed: {} → {}", lastRegime, currentRegime);
        lastRegime = currentRegime;

        // Cari alasan HOLD (filter yang fail)
        String holdReason = "No actionable signal";
        if (signal.getFilters() != null && !signal.getFilters().isEmpty()) {
            holdReason = signal.getFilters().stream()
                    .filter(f -> !f.isPass())
                    .map(f -> "[" + f.getFilterName() + "] " + f.getReason())
                    .findFirst()
                    .orElse(signal.getSummary() != null
                            ? signal.getSummary() : "No reason");
        } else if (signal.getSummary() != null) {
            holdReason = signal.getSummary();
        }

        // Build Telegram message
        StringBuilder msg = new StringBuilder();
        msg.append(String.format("📊 Regime: <b>%s</b>\n", currentRegime));
        msg.append(String.format("📈 Strategy: <b>%s</b>\n\n", signal.getStrategy()));
        msg.append(String.format("❌ Hold Reason:\n%s\n\n", holdReason));

        // Tambah indicator summary
        msg.append("<b>Market Condition:</b>\n");
        msg.append(String.format("💰 Price: $%.4f\n", snapshot.getCurrentPrice().doubleValue()));
        msg.append(String.format("📊 ADX: %.2f [%s]\n",
                snapshot.getAdx().doubleValue(),
                currentRegime));
        msg.append(String.format("📈 +DI: %.2f | -DI: %.2f\n",
                snapshot.getPlusDI().doubleValue(),
                snapshot.getMinusDI().doubleValue()));
        msg.append(String.format("📉 RSI: %.2f [%s]\n",
                snapshot.getRsi().doubleValue(),
                snapshot.getRsiZone()));
        msg.append(String.format("📦 Volume Ratio: %.2fx [%s]\n",
                snapshot.getVolumeRatio().doubleValue(),
                snapshot.getVolumeZone()));

        // Filter results
        if (signal.getFilters() != null && !signal.getFilters().isEmpty()) {
            msg.append("\n<b>Filter Results:</b>\n");
            signal.getFilters().forEach(f -> {
                String icon = f.isPass() ? "✅" : "❌";
                msg.append(String.format("%s %s\n", icon, f.getReason()));
            });
        }

        msg.append(String.format("\n⏰ %s WIB",
                java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter
                                .ofPattern("dd-MM-yyyy HH:mm:ss"))));

        telegramService.sendMessage("⏸️ HOLD — Regime Changed", msg.toString());
    }

    private String formatTime() {
        return java.time.LocalDateTime.now(ZoneId.of("Asia/Jakarta"))
                .format(java.time.format.DateTimeFormatter
                        .ofPattern("dd-MM-yyyy HH:mm:ss"));
    }
}

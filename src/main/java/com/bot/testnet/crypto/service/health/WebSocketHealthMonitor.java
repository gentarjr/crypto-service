package com.bot.testnet.crypto.service.health;

import com.bot.testnet.crypto.service.TelegramNotificationService;
import com.bot.testnet.crypto.service.websocket.BinanceWebSocketService;
import com.bot.testnet.crypto.service.websocket.PriceCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Log4j2
public class WebSocketHealthMonitor {

    private final PriceCache priceCache;
    private final BinanceWebSocketService webSocketService;
    private final TelegramNotificationService telegramService;

    @Value("${trading.websocket.health.idle-threshold-seconds:90}")
    private long idleThresholdSeconds;

    @Value("${trading.websocket.health.enabled:true}")
    private boolean healthEnabled;

    // true = lagi dalam kondisi "alert sudah dikirim", supaya tidak spam
    private boolean alertActive = false;

    @Scheduled(fixedRate = 60000) // cek tiap 60 detik
    public void checkWebSocketHealth() {
        if (!healthEnabled) return;

        Instant lastTick = priceCache.getLastUpdateTime();

        // Belum pernah ada tick sama sekali sejak start → tunggu dulu, jangan alert
        if (lastTick == null) {
            log.debug("WS health: belum ada tick sejak start, skip");
            return;
        }

        long idleSec = Duration.between(lastTick, Instant.now()).getSeconds();
        boolean wsOpen = webSocketService.isConnected();

        // Kondisi tidak sehat: terlalu lama tidak ada tick ATAU socket memang tertutup
        boolean unhealthy = idleSec > idleThresholdSeconds || !wsOpen;

        if (unhealthy && !alertActive) {
            alertActive = true;
            log.warn("⚠️ WS unhealthy: idle={}s, open={}", idleSec, wsOpen);
            telegramService.sendMessage(
                    "⚠️ WebSocket Bermasalah",
                    String.format(
                            "Price feed berhenti.\n\n" +
                                    "Tick terakhir: <b>%d detik lalu</b>\n" +
                                    "Socket open: <b>%s</b>\n" +
                                    "Status: <b>%s</b>\n\n" +
                                    "Bot lagi auto-reconnect. Pantau.\n⏰ %s WIB",
                            idleSec, wsOpen, webSocketService.getStatus(), now()));

        } else if (!unhealthy && alertActive) {
            alertActive = false;
            log.info("✅ WS pulih: idle={}s", idleSec);
            telegramService.sendMessage(
                    "✅ WebSocket Pulih",
                    String.format(
                            "Price feed normal lagi.\n" +
                                    "Tick terakhir: %d detik lalu\n⏰ %s WIB",
                            idleSec, now()));
        }
    }

    private String now() {
        return java.time.LocalDateTime.now(ZoneId.of("Asia/Jakarta"))
                .format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"));
    }
}
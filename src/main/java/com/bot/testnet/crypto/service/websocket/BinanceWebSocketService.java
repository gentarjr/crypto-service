package com.bot.testnet.crypto.service.websocket;

import com.bot.testnet.crypto.model.dto.PriceTick;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.math.BigDecimal;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Log4j2
public class BinanceWebSocketService {

    private final PriceCache priceCache;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${trading.websocket.base-url:wss://stream.binance.vision/ws}")
    private String wsBaseUrl;

    @Value("${trading.pair.base:BNB}")
    private String baseCurrency;

    @Value("${trading.pair.quote:USDT}")
    private String quoteCurrency;

    @Value("${trading.websocket.reconnect-delay-seconds:5}")
    private int reconnectDelaySeconds;

    @Value("${trading.websocket.max-reconnect-attempts:10}")
    private int maxReconnectAttempts;

    @Value("${trading.websocket.enabled:true}")
    private boolean wsEnabled;

    private WebSocketClient wsClient;
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(false);
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-reconnect");
                t.setDaemon(true);
                return t;
            });

    // ═══════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (wsEnabled) {
            log.info("🚀 Auto-starting WebSocket...");
            start();
        } else {
            log.warn("📵 WebSocket disabled in config");
        }
    }

    @PreDestroy
    public void onShutdown() {
        log.info("🛑 Shutting down WebSocket...");
        stop();
        reconnectExecutor.shutdown();
    }

    // ═══════════════════════════════════════════════════
    // Public Methods
    // ═══════════════════════════════════════════════════

    public void start() {
        if (isConnected.get()) {
            log.warn("WebSocket already connected");
            return;
        }
        shouldReconnect.set(true);
        reconnectAttempts.set(0);
        connect();
    }

    public void stop() {
        shouldReconnect.set(false);
        isConnected.set(false);
        if (wsClient != null) {
            wsClient.close();
            wsClient = null;
        }
    }

    public boolean isConnected() {
        return isConnected.get()
                && wsClient != null
                && wsClient.isOpen();
    }

    public String getStatus() {
        if (isConnected()) return "CONNECTED";
        if (shouldReconnect.get()) return "CONNECTING";
        return "STOPPED";
    }

    // ═══════════════════════════════════════════════════
    // Private: Connection
    // ═══════════════════════════════════════════════════

    private void connect() {
        try {
            String symbol = (baseCurrency + quoteCurrency).toLowerCase();
            String url = String.format("%s/%s@trade", wsBaseUrl, symbol);
            log.info("🔌 Connecting: {}", url);

            // ✅ FIX 1: Trust-all SSL untuk testnet
            SSLContext sslContext = buildTrustAllSslContext();

            wsClient = new WebSocketClient(new URI(url)) {

                @Override
                public void onOpen(ServerHandshake handshake) {
                    log.info("✅ WebSocket connected: {}", getURI());
                    isConnected.set(true);            // ✅ FIX 2: set isConnected di sini
                    reconnectAttempts.set(0);
                }

                @Override
                public void onMessage(String message) {
                    handleMessage(message, symbol.toUpperCase());
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    log.warn("⚠️ WebSocket closed: code={}, reason={}, remote={}",
                            code, reason, remote);
                    isConnected.set(false);

                    // ✅ FIX 3: Reconnect HANYA dari onClose (tidak dari onError)
                    if (shouldReconnect.get()) {
                        scheduleReconnect();
                    }
                }

                @Override
                public void onError(Exception ex) {
                    // ✅ FIX 3: Log only, JANGAN schedule reconnect
                    // onClose akan dipanggil setelah onError → reconnect dari sana
                    log.error("❌ WebSocket error: {}", ex.getMessage());
                }
            };

            // Apply SSL
            wsClient.setSocketFactory(sslContext.getSocketFactory());

            // Connect (blocking, timeout 10s)
            boolean connected = wsClient.connectBlocking(10, TimeUnit.SECONDS);

            if (!connected) {
                log.error("❌ Failed to connect within timeout");
                isConnected.set(false);
                if (shouldReconnect.get()) {
                    scheduleReconnect();
                }
            }

        } catch (Exception e) {
            log.error("❌ Connection error: {}", e.getMessage());
            isConnected.set(false);
            if (shouldReconnect.get()) {
                scheduleReconnect();
            }
        }
    }

    private void scheduleReconnect() {
        int attempt = reconnectAttempts.incrementAndGet();

        if (attempt > maxReconnectAttempts) {
            log.error("❌ Max reconnect attempts ({}) reached. Stopping.", maxReconnectAttempts);
            shouldReconnect.set(false);
            return;
        }

        // Exponential backoff: 5s, 10s, 15s, ...
        int delaySeconds = reconnectDelaySeconds * attempt;
        log.warn("⏳ Reconnect attempt #{} in {}s...", attempt, delaySeconds);

        reconnectExecutor.schedule(this::connect, delaySeconds, TimeUnit.SECONDS);
    }

    // ═══════════════════════════════════════════════════
    // Private: Message Handling
    // ═══════════════════════════════════════════════════

    private void handleMessage(String message, String symbol) {
        try {
            JsonNode json = objectMapper.readTree(message);
            String eventType = json.path("e").asText();

            if ("trade".equals(eventType)) {
                BigDecimal price = new BigDecimal(json.path("p").asText());
                BigDecimal quantity = new BigDecimal(json.path("q").asText());
                long tradeTime = json.path("T").asLong();
                boolean isBuyerMaker = json.path("m").asBoolean();

                PriceTick tick = PriceTick.builder()
                        .symbol(symbol)
                        .price(price)
                        .quantity(quantity)
                        .timestamp(Instant.ofEpochMilli(tradeTime))
                        .isBuyerMaker(isBuyerMaker)
                        .build();

                priceCache.updatePrice(tick);
            }
        } catch (Exception e) {
            log.error("❌ Error parsing message: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════
    // Private: SSL
    // ═══════════════════════════════════════════════════

    /**
     * Build SSL context yang trust semua certificate
     *
     * ⚠️ Ini acceptable untuk testnet (self-signed cert)
     * Untuk production: pakai proper certificate validation
     */
    private SSLContext buildTrustAllSslContext() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        return sslContext;
    }
}
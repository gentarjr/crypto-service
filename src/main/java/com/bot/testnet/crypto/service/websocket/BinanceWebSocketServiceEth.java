package com.bot.testnet.crypto.service.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bot.testnet.crypto.model.dto.PriceTick;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(name = "trading.pair-eth.enabled", havingValue = "true")
@RequiredArgsConstructor
@Log4j2
public class BinanceWebSocketServiceEth {

    private final PriceCacheEth priceCacheEth;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${trading.websocket-eth.base-url:wss://stream.binance.com/ws}")
    private String wsBaseUrl;

    @Value("${trading.pair-eth.base:ETH}")
    private String baseCurrency;

    @Value("${trading.pair-eth.quote:USDC}")
    private String quoteCurrency;

    @Value("${trading.websocket-eth.reconnect-delay-seconds:5}")
    private int reconnectDelaySeconds;

    @Value("${trading.websocket-eth.max-reconnect-attempts:10}")
    private int maxReconnectAttempts;

    @Value("${trading.websocket-eth.enabled:false}")
    private boolean wsEnabled;

    @Value("${exchange.binance.testnet:true}")
    private boolean testnet;

    private WebSocketClient wsClient;
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(false);
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-reconnect-eth");
                t.setDaemon(true);
                return t;
            });

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (wsEnabled) {
            log.info("🚀 [ETH] Auto-starting WebSocket...");
            start();
        } else {
            log.warn("📵 [ETH] WebSocket disabled in config");
        }
    }

    @PreDestroy
    public void onShutdown() {
        log.info("🛑 [ETH] Shutting down WebSocket...");
        stop();
        reconnectExecutor.shutdown();
    }

    public void start() {
        if (isConnected.get()) {
            log.warn("[ETH] WebSocket already connected");
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

    private void connect() {
        try {
            String symbol = (baseCurrency + quoteCurrency).toLowerCase();
            String url = String.format("%s/%s@trade", wsBaseUrl, symbol);
            log.info("🔌 [ETH] Connecting: {}", url);

            SSLContext sslContext = buildTrustAllSslContext();

            wsClient = new WebSocketClient(new URI(url)) {

                @Override
                public void onOpen(ServerHandshake handshake) {
                    log.info("✅ [ETH] WebSocket connected: {}", getURI());
                    isConnected.set(true);
                    reconnectAttempts.set(0);
                }

                @Override
                public void onMessage(String message) {
                    handleMessage(message, symbol.toUpperCase());
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    log.warn("⚠️ [ETH] WebSocket closed: code={}, reason={}, remote={}",
                            code, reason, remote);
                    isConnected.set(false);
                    if (shouldReconnect.get()) {
                        scheduleReconnect();
                    }
                }

                @Override
                public void onError(Exception ex) {
                    log.error("❌ [ETH] WebSocket error: {}", ex.getMessage());
                }
            };

            wsClient.setSocketFactory(sslContext.getSocketFactory());

            boolean connected = wsClient.connectBlocking(10, TimeUnit.SECONDS);

            if (!connected) {
                log.error("❌ [ETH] Failed to connect within timeout");
                isConnected.set(false);
                if (shouldReconnect.get()) {
                    scheduleReconnect();
                }
            }

        } catch (Exception e) {
            log.error("❌ [ETH] Connection error: {}", e.getMessage());
            isConnected.set(false);
            if (shouldReconnect.get()) {
                scheduleReconnect();
            }
        }
    }

    private void scheduleReconnect() {
        int attempt = reconnectAttempts.incrementAndGet();

        if (attempt > maxReconnectAttempts) {
            log.error("❌ [ETH] Max reconnect attempts ({}) reached. Stopping.", maxReconnectAttempts);
            shouldReconnect.set(false);
            return;
        }

        int delaySeconds = reconnectDelaySeconds * attempt;
        log.warn("⏳ [ETH] Reconnect attempt #{} in {}s...", attempt, delaySeconds);

        reconnectExecutor.schedule(this::connect, delaySeconds, TimeUnit.SECONDS);
    }

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

                priceCacheEth.updatePrice(tick);
            }
        } catch (Exception e) {
            log.error("❌ [ETH] Error parsing message: {}", e.getMessage());
        }
    }

    private SSLContext buildTrustAllSslContext() throws Exception {
        if (!testnet) {
            log.info("🔒 [ETH] Using default SSL (mainnet)");
            return SSLContext.getDefault();
        }

        log.info("⚠️ [ETH] Using trust-all SSL (testnet)");
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
package com.bot.testnet.crypto.service.websocket;

import com.bot.testnet.crypto.model.dto.PriceTick;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * WebSocket client untuk Binance trade stream
 *
 * Subscribe ke: wss://stream.binance.vision/ws/{symbol}@trade
 * Receive: real-time trade data (price, qty, timestamp)
 */
@Log4j2
public class BinanceWebSocketClient extends WebSocketClient {

    private final String symbol;
    private final Consumer<PriceTick> onPriceTick;   // callback saat dapat price baru
    private final Consumer<Exception> onError;        // callback saat error
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BinanceWebSocketClient(URI serverUri,
                                  String symbol,
                                  Consumer<PriceTick> onPriceTick,
                                  Consumer<Exception> onError) {
        super(serverUri);
        this.symbol = symbol;
        this.onPriceTick = onPriceTick;
        this.onError = onError;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("✅ WebSocket connected: {} (HTTP {})",
                getURI(), handshake.getHttpStatus());
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);

            // Binance trade stream format:
            // { "e": "trade", "s": "BNBUSDT", "p": "661.87", "q": "0.5", "T": 1234567890, "m": false }
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

                onPriceTick.accept(tick);
            }
        } catch (Exception e) {
            log.error("❌ Error parsing WebSocket message: {}", e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.warn("⚠️ WebSocket closed: code={}, reason={}, remote={}",
                code, reason, remote);
    }

    @Override
    public void onError(Exception ex) {
        log.error("❌ WebSocket error: {}", ex.getMessage());
        onError.accept(ex);
    }
}
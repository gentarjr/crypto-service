package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.service.websocket.BinanceWebSocketService;
import com.bot.testnet.crypto.service.websocket.PriceCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ws")
@RequiredArgsConstructor
@Log4j2
public class WebSocketController {

    private final BinanceWebSocketService webSocketService;
    private final PriceCache priceCache;

    @PostMapping("/start")
    public Map<String, String> start() {
        webSocketService.start();
        return Map.of("status", "STARTING", "message", "WebSocket connection initiated");
    }

    @PostMapping("/stop")
    public Map<String, String> stop() {
        webSocketService.stop();
        return Map.of("status", "STOPPED", "message", "WebSocket connection closed");
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "connected", webSocketService.isConnected(),
                "status", webSocketService.getStatus(),
                "latestPrice", priceCache.getLatestPrice() != null
                        ? priceCache.getLatestPrice() : "N/A",
                "isFresh", priceCache.isFresh(),
                "lastUpdate", priceCache.getLastUpdateTime() != null
                        ? priceCache.getLastUpdateTime().toString() : "N/A"
        );
    }
}
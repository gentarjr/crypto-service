package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.model.LivePosition;
import com.bot.testnet.crypto.service.trading.OrderExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/live")
@RequiredArgsConstructor
@Log4j2
public class LiveTradingController {

    private final OrderExecutorService orderExecutorService;

    @GetMapping("/status")
    public Map<String, Object> status() {
        LivePosition pos = orderExecutorService.getOpenPosition();
        return Map.of(
                "enabled", orderExecutorService.isEnabled(),
                "halted", orderExecutorService.isHalted(),
                "openPosition", pos != null ? pos : "none",
                "closedCount", orderExecutorService.getClosedPositions().size()
        );
    }

    @GetMapping("/positions")
    public List<LivePosition> closedPositions() {
        return orderExecutorService.getClosedPositions();
    }
}
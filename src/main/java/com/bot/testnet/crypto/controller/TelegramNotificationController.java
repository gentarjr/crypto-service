package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.model.request.GetTelegramChatRequest;
import com.bot.testnet.crypto.model.response.GetTelegramChatResponse;
import com.bot.testnet.crypto.service.TelegramNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Log4j2
public class TelegramNotificationController {

    private final TelegramNotificationService telegramNotificationService;

    @PostMapping("/telegram/chat")
    public GetTelegramChatResponse testTelegram(@RequestBody GetTelegramChatRequest request) {
        return telegramNotificationService.sendMessage(request);
    }
}

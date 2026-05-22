package com.bot.testnet.crypto.service;

import com.bot.testnet.crypto.adaptor.TelegramNotificationAdaptor;
import com.bot.testnet.crypto.model.request.GetTelegramChatRequest;
import com.bot.testnet.crypto.model.response.GetTelegramChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;


@Service
@Log4j2
@RequiredArgsConstructor
public class TelegramNotificationService {

    private final TelegramNotificationAdaptor telegramNotificationAdaptor;

    @Value("${telegram.enabled:true}")
    private boolean isEnabled;

    @Value("${telegram.bot-token}")
    private String botToken;

    /**
     * Send message dengan title + message (HTML formatted)
     * Dipanggil dari controller
     */
    public GetTelegramChatResponse sendMessage(GetTelegramChatRequest request) {
        if (!isEnabled) {
            log.warn("📵 Telegram notification is DISABLED");
            return GetTelegramChatResponse.builder()
                    .status("DISABLED")
                    .message("Telegram is disabled in config")
                    .build();
        }

        String formatted = formatMessage(request.getTitle(), request.getMessage());
        log.info("📱 Sending Telegram message. Token: {}", maskToken(botToken));

        telegramNotificationAdaptor.sendMessage(formatted, botToken);

        return GetTelegramChatResponse.builder()
                .status("SENT")
                .message("Check your Telegram!")
                .build();
    }

    /**
     * Convenience method — dipanggil dari service lain (Phase 2.1+)
     * Misalnya: TradingService.notifyTradeOpened()
     */
    public void sendMessage(String title, String message) {
        if (!isEnabled) {
            log.debug("Telegram disabled, skip message: {}", title);
            return;
        }

        String formatted = formatMessage(title, message);
        telegramNotificationAdaptor.sendMessage(formatted, botToken);
    }

    /**
     * Format message dengan title + separator
     */
    private String formatMessage(String title, String message) {
        return "<b>" + escapeHtml(title) + "</b>\n" +
                "━━━━━━━━━━━━━━━━━━\n\n" +
                safeMessage(message);
    }

    /**
     * Safe message: escape semua < dan > KECUALI tag HTML yang kita tulis sendiri
     * Caranya: escape dulu semua, lalu kembalikan tag yang valid
     */
    private String safeMessage(String message) {
        if (message == null) return "";

        // Step 1: Escape semua & < >
        String escaped = message
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");

        // Step 2: Kembalikan tag HTML valid yang kita pakai
        return escaped
                .replace("&lt;b&gt;", "<b>")
                .replace("&lt;/b&gt;", "</b>")
                .replace("&lt;i&gt;", "<i>")
                .replace("&lt;/i&gt;", "</i>")
                .replace("&lt;code&gt;", "<code>")
                .replace("&lt;/code&gt;", "</code>");
    }

    /**
     * Escape HTML untuk hindari conflict dengan parse_mode HTML
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * Mask token untuk safe logging
     */
    private String maskToken(String token) {
        if (token == null || token.length() < 8) return "***";
        return token.substring(0, 8) + "***";
    }
}

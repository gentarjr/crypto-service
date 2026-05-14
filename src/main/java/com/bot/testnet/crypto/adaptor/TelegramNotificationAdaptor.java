package com.bot.testnet.crypto.adaptor;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

@Component
@Log4j2
public class TelegramNotificationAdaptor {

    @Value("${telegram.chat-id}")
    private String chatId;

    @Value("${telegram.parse-mode:HTML}")
    private String parseMode;

    @Value("${telegram.api-url:https://api.telegram.org}")
    private String apiUrl;

    private final RestClient restClient;

    /**
     * Constructor — init RestClient saat bean dibuat
     */
    public TelegramNotificationAdaptor() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);  // 5 detik
        factory.setReadTimeout(10_000);    // 10 detik

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /**
     * Send message ke Telegram via HTTP API
     *
     * @param message  text yang akan dikirim
     * @param botToken bot token dari @BotFather
     */
    public String sendMessage(String message, String botToken) {
        try {
            String url = String.format("%s/bot%s/sendMessage", apiUrl, botToken);

            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", chatId);
            body.put("text", message);
            body.put("parse_mode", parseMode);

            return restClient.post()
                    .uri(url)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.error("❌ Failed to send Telegram message: {}", e.getMessage());
            return "FAILED";
        }
    }
}

package com.bot.testnet.crypto.service.exchange;

import com.bot.testnet.crypto.model.request.OcoOrderRequest;
import com.bot.testnet.crypto.model.response.OcoOrderResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Formatter;

@Service
@Log4j2
public class BinanceOcoService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${exchange.binance.testnet:true}")
    private boolean testnet;

    @Value("${exchange.binance.api-key}")
    private String apiKey;

    @Value("${exchange.binance.secret-key}")
    private String secretKey;

    @Value("${trading.oco.enabled:true}")
    private boolean ocoEnabled;

    private static final String MAINNET_URL = "https://api.binance.com";
    private static final String HMAC_SHA256  = "HmacSHA256";

    /**
     * Place OCO order setelah BUY berhasil
     * Pakai Binance REST API langsung (XChange tidak support OCO)
     */
    public OcoOrderResponse placeOcoOrder(OcoOrderRequest request) {
        if (testnet || !ocoEnabled) {
            log.info("ℹ️ OCO skipped — testnet mode");
            return OcoOrderResponse.builder()
                    .status("SKIPPED")
                    .errorMessage("Testnet mode — OCO skipped")
                    .build();
        }

        try {
            String symbol = (request.getBase() + request.getQuote()).toUpperCase();
            BigDecimal qty     = request.getQuantity()
                    .setScale(2, RoundingMode.DOWN);
            BigDecimal tpPrice = request.getTakeProfitPrice()
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal slPrice = request.getStopLossPrice()
                    .setScale(2, RoundingMode.DOWN);
            // Stop limit sedikit di bawah SL untuk ensure trigger
            BigDecimal slLimit = slPrice
                    .multiply(new BigDecimal("0.999"))
                    .setScale(2, RoundingMode.DOWN);

            if (tpPrice.compareTo(slPrice) <= 0) {
                log.warn("⚠️ OCO skip: TP ${} <= SL ${}", tpPrice, slPrice);
                return OcoOrderResponse.builder()
                        .status("SKIPPED")
                        .errorMessage("TP must be greater than SL")
                        .build();
            }

            long timestamp = System.currentTimeMillis();

            String params = "symbol=" + symbol +
                    "&side=SELL" +
                    "&quantity=" + qty.toPlainString() +
                    "&aboveType=LIMIT_MAKER" +
                    "&abovePrice=" + tpPrice.toPlainString() +
                    "&belowType=STOP_LOSS_LIMIT" +
                    "&belowStopPrice=" + slPrice.toPlainString() +
                    "&belowPrice=" + slLimit.toPlainString() +
                    "&belowTimeInForce=GTC" +
                    "&timestamp=" + timestamp;

            String signature = hmacSha256(secretKey, params);
            String url = MAINNET_URL + "/api/v3/orderList/oco?" + params
                    + "&signature=" + signature;

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-MBX-APIKEY", apiKey);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<String> entity = new HttpEntity<>(headers);

            log.info("📋 Placing OCO: {} | qty={} | TP=${} | SL=${} | SL-limit=${}",
                    symbol, qty, tpPrice, slPrice, slLimit);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                // Extract orderListId dari response JSON
                String body = response.getBody();
                String orderListId = extractJsonValue(body, "orderListId");
                log.info("✅ OCO placed: orderListId={}", orderListId);
                return OcoOrderResponse.builder()
                        .orderListId(orderListId)
                        .status("SUCCESS")
                        .build();
            } else {
                log.error("❌ OCO failed: {}", response.getBody());
                return OcoOrderResponse.builder()
                        .status("FAILED")
                        .errorMessage(response.getBody())
                        .build();
            }

        } catch (Exception e) {
            log.error("❌ OCO error: {}", e.getMessage());
            return OcoOrderResponse.builder()
                    .status("FAILED")
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * Cancel OCO order sebelum bot close posisi
     * Hindari double sell (OCO + bot sell)
     */
    public void cancelOcoOrder(String symbol, String orderListId) {
        if (testnet || orderListId == null) return;
        try {
            long timestamp = System.currentTimeMillis();
            String params = "symbol=" + symbol +
                    "&orderListId=" + orderListId +
                    "&timestamp=" + timestamp;
            String signature = hmacSha256(secretKey, params);
            String url = MAINNET_URL + "/api/v3/orderList?"
                    + params + "&signature=" + signature;

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-MBX-APIKEY", apiKey);

            restTemplate.exchange(url, HttpMethod.DELETE,
                    new HttpEntity<>(headers), String.class);

            log.info("✅ OCO cancelled: {}", orderListId);
        } catch (Exception e) {
            log.warn("⚠️ Cannot cancel OCO {}: {}", orderListId, e.getMessage());
        }
    }

    // ─── Helpers ──────────────────────────────────────────

    private String hmacSha256(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance(HMAC_SHA256);
        SecretKeySpec secretKey = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
        mac.init(secretKey);
        byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        Formatter formatter = new Formatter();
        for (byte b : bytes) {
            formatter.format("%02x", b);
        }
        return formatter.toString();
    }

    private String extractJsonValue(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int start = idx + search.length();
        // Skip whitespace
        while (start < json.length() && json.charAt(start) == ' ') start++;
        // Could be number or string
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        } else {
            int end = start;
            while (end < json.length()
                    && json.charAt(end) != ','
                    && json.charAt(end) != '}') end++;
            return json.substring(start, end).trim();
        }
    }
}
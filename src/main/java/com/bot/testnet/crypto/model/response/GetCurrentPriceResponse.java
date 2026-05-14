package com.bot.testnet.crypto.model.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class GetCurrentPriceResponse {
    private String pair;
    private BigDecimal price;
    private String timestamp;
}

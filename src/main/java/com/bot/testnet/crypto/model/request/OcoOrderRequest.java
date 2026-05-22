package com.bot.testnet.crypto.model.request;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class OcoOrderRequest {
    private String base;
    private String quote;
    private BigDecimal quantity;
    private BigDecimal takeProfitPrice;
    private BigDecimal stopLossPrice;
}
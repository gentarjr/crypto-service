package com.bot.testnet.crypto.model.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PostBuyResponse {

    private String orderId;
    private String status;
    private String timestamp;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private BigDecimal filledAmount;
    private String note;
    private String errorMessage;
}

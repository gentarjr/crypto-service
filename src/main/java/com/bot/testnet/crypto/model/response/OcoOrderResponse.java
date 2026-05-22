package com.bot.testnet.crypto.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OcoOrderResponse {
    private String orderListId;
    private String status;        // SUCCESS, FAILED, SKIPPED
    private String errorMessage;
}
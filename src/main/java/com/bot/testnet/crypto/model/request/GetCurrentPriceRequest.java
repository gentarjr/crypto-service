package com.bot.testnet.crypto.model.request;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetCurrentPriceRequest {
    private String base;
    private String quote;
}

package com.bot.testnet.crypto.model.request;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetBalanceCurrencyRequest {
    private String currency;
}

package com.bot.testnet.crypto.model.request;

import lombok.Builder;
import lombok.Data;
import org.knowm.xchange.binance.dto.marketdata.KlineInterval;

@Data
@Builder
public class GetCandleRequest {
    private String base;
    private String quote;
    private KlineInterval interval;
    private int limit;
}

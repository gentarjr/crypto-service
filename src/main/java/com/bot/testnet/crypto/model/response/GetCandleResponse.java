package com.bot.testnet.crypto.model.response;

import com.bot.testnet.crypto.model.dto.Candle;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GetCandleResponse {
    private String pair;
    private String interval;
    private int count;
    private List<Candle> candle;
}

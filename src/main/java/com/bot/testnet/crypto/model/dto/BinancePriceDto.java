package com.bot.testnet.crypto.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Mapping response Binance GET /api/v3/ticker/price?symbol=X
 * Beda struktur dengan BinanceTicker24hDto (single object, bukan array bulk).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinancePriceDto {
    private String symbol;
    private String price;
}
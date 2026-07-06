package com.bot.testnet.crypto.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Mapping parsial response Binance GET /api/v3/ticker/24hr (tanpa symbol param
 * = bulk, return semua pair). Cuma field yang dipakai screener yang di-map;
 * field lain diabaikan lewat @JsonIgnoreProperties.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceTicker24hDto {
    private String symbol;
    private String priceChangePercent; // string dari Binance, perlu di-parse BigDecimal
    private String lastPrice;
    private String quoteVolume; // volume 24h dalam quote asset (USDT)
    private String volume;      // volume 24h dalam base asset
}
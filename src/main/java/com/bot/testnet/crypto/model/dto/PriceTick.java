package com.bot.testnet.crypto.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Real-time price tick dari WebSocket
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceTick {
    private String symbol;          // "BNBUSDT"
    private BigDecimal price;       // harga trade
    private BigDecimal quantity;    // volume trade ini
    private Instant timestamp;      // kapan trade terjadi
    private boolean isBuyerMaker;   // buyer = maker? (bid/ask info)
}
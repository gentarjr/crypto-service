package com.bot.testnet.crypto.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "trade_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeHistory {

    @Id
    @Column(name = "id", length = 20)
    private String id;

    @Column(name = "pair", length = 20)
    private String pair;

    @Column(name = "strategy", length = 30)
    private String strategy;

    @Column(name = "entry_price", precision = 18, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "close_price", precision = 18, scale = 8)
    private BigDecimal closePrice;

    @Column(name = "quantity", precision = 18, scale = 8)
    private BigDecimal quantity;

    @Column(name = "position_value", precision = 18, scale = 8)
    private BigDecimal positionValue;

    @Column(name = "pnl_after_fee", precision = 18, scale = 8)
    private BigDecimal pnlAfterFee;

    @Column(name = "pnl_percent", precision = 10, scale = 4)
    private BigDecimal pnlPercent;

    @Column(name = "fee", precision = 18, scale = 8)
    private BigDecimal fee;

    @Column(name = "close_reason", length = 30)
    private String closeReason;

    @Column(name = "open_time")
    private Instant openTime;

    @Column(name = "close_time")
    private Instant closeTime;

    @Column(name = "partial_tp_executed")
    private boolean partialTpExecuted;

    @Column(name = "partial_tp_pnl", precision = 18, scale = 8)
    private BigDecimal partialTpPnl;

    @Column(name = "duration_minutes")
    private Long durationMinutes;
}
package com.bot.testnet.crypto.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "equity_tracking")
@Getter
@Setter
public class EquityTrackingEntity {

    @Id
    private String pairScope; // "BNB" atau "ETH"

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal peakEquity;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal currentEquity;

    @Column(nullable = false)
    private Instant peakTimestamp;

    @Column(nullable = false)
    private boolean drawdownBreached;

    private Instant breachTimestamp;

    @Column(precision = 20, scale = 8)
    private BigDecimal breachEquity;

    @Column(nullable = false)
    private Instant lastUpdated;
}
package com.bot.testnet.crypto.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "coin_candidate")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinCandidate {

    @Id
    @Column(name = "symbol", length = 20)
    private String symbol; // contoh: SOLUSDT

    @Column(name = "score", precision = 10, scale = 4)
    private BigDecimal score;

    @Column(name = "price_change_percent_4h", precision = 10, scale = 4)
    private BigDecimal priceChangePercent4h;

    @Column(name = "relative_strength_vs_btc", precision = 10, scale = 4)
    private BigDecimal relativeStrengthVsBtc;

    @Column(name = "volume_spike_ratio", precision = 10, scale = 4)
    private BigDecimal volumeSpikeRatio;

    @Column(name = "last_price", precision = 18, scale = 8)
    private BigDecimal lastPrice;

    @Column(name = "quote_volume_24h", precision = 20, scale = 4)
    private BigDecimal quoteVolume24h;

    @Column(name = "rank_position")
    private Integer rankPosition; // 1-10

    @Column(name = "generated_at")
    private Instant generatedAt;
}
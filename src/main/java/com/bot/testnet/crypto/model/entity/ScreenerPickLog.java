package com.bot.testnet.crypto.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "screener_pick_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScreenerPickLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", length = 20)
    private String symbol;

    @Column(name = "picked_at")
    private Instant pickedAt;

    @Column(name = "entry_price", precision = 18, scale = 8)
    private BigDecimal entryPrice;

    @Column(name = "score_at_pick", precision = 10, scale = 4)
    private BigDecimal scoreAtPick;

    @Column(name = "verdict_at_pick", length = 20)
    private String verdictAtPick; // KUAT/SEDANG/LEMAH — buat breakdown hit rate per tier nanti

    @Column(name = "sl_at_pick", precision = 18, scale = 8)
    private BigDecimal slAtPick;

    @Column(name = "tp_at_pick", precision = 18, scale = 8)
    private BigDecimal tpAtPick;

    @Column(name = "rank_at_pick")
    private Integer rankAtPick;

    @Column(name = "price_24h", precision = 18, scale = 8)
    private BigDecimal price24h;

    @Column(name = "change_percent_24h", precision = 10, scale = 4)
    private BigDecimal changePercent24h;

    @Column(name = "outcome_24h", length = 20)
    private String outcome24h; // TP_HIT / SL_HIT / NEITHER — mana yang kesentuh DULUAN, bukan cuma titik akhir

    @Column(name = "checked_24h")
    private boolean checked24h;

    @Column(name = "price_48h", precision = 18, scale = 8)
    private BigDecimal price48h;

    @Column(name = "change_percent_48h", precision = 10, scale = 4)
    private BigDecimal changePercent48h;

    @Column(name = "outcome_48h", length = 20)
    private String outcome48h;

    @Column(name = "checked_48h")
    private boolean checked48h;
}
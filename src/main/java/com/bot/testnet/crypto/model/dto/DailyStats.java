package com.bot.testnet.crypto.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Statistik trading harian
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyStats {

    private LocalDate date;
    private BigDecimal startingCapital;      // modal awal hari ini
    private BigDecimal totalPnl;             // total P&L hari ini
    private int totalTrades;                 // jumlah trade
    private int wins;                        // jumlah win
    private int losses;                      // jumlah loss
    private int consecutiveLosses;          // loss berturut-turut sekarang
    private List<TradeRecord> trades;        // semua trade hari ini

    @Builder.Default
    private boolean isHalted = false;        // bot stop karena daily limit?

    /**
     * Win rate %
     */
    public double getWinRate() {
        if (totalTrades == 0) return 0.0;
        return (double) wins / totalTrades * 100;
    }

    /**
     * Total P&L dalam %
     */
    public BigDecimal getTotalPnlPercent() {
        if (startingCapital == null || startingCapital.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return totalPnl.divide(startingCapital, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * Tambah trade result
     */
    public void addTrade(TradeRecord record) {
        if (trades == null) trades = new ArrayList<>();
        trades.add(record);
        totalTrades++;
        if (record.isWin()) {
            wins++;
            consecutiveLosses = 0;
        } else {
            losses++;
            consecutiveLosses++;
        }
        totalPnl = totalPnl.add(record.getPnl());
    }
}
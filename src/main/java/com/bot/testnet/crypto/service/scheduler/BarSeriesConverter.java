package com.bot.testnet.crypto.service.scheduler;

import com.bot.testnet.crypto.model.dto.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

@Component
@Slf4j
public class BarSeriesConverter {

    /**
     * Convert List<Candle> ke BarSeries
     *
     * @param candles      list candle dari cache
     * @param seriesName   nama untuk identification (e.g., "BNBUSDT_15m")
     * @return BarSeries siap pakai untuk indikator
     */
    public BarSeries convert(List<Candle> candles, String seriesName) {
        BarSeries series = new BaseBarSeries(seriesName);

        if (candles.isEmpty()) {
            log.warn("⚠️ Empty candle list, returning empty BarSeries");
            return series;
        }

        // Determine bar duration dari interval candle pertama
        Duration barDuration = parseDuration(candles.get(0).getInterval());

        for (Candle candle : candles) {
            ZonedDateTime endTime = candle.getCloseTime().atZone(ZoneOffset.UTC);

            try {
                series.addBar(BaseBar.builder()
                        .timePeriod(barDuration)
                        .endTime(endTime)
                        .openPrice(series.numOf(candle.getOpen()))
                        .highPrice(series.numOf(candle.getHigh()))
                        .lowPrice(series.numOf(candle.getLow()))
                        .closePrice(series.numOf(candle.getClose()))
                        .volume(series.numOf(candle.getVolume()))
                        .build());
            } catch (Exception e) {
                log.error("❌ Failed to add bar at {}: {}", endTime, e.getMessage());
            }
        }

        log.debug("✅ Converted {} candles to BarSeries", series.getBarCount());
        return series;
    }

    /**
     * Parse interval string ke Duration
     */
    private Duration parseDuration(String interval) {
        // KlineInterval names: m1, m5, m15, h1, h4, d1
        return switch (interval) {
            case "m1" -> Duration.ofMinutes(1);
            case "m3" -> Duration.ofMinutes(3);
            case "m5" -> Duration.ofMinutes(5);
            case "m15" -> Duration.ofMinutes(15);
            case "m30" -> Duration.ofMinutes(30);
            case "h1" -> Duration.ofHours(1);
            case "h2" -> Duration.ofHours(2);
            case "h4" -> Duration.ofHours(4);
            case "h6" -> Duration.ofHours(6);
            case "h8" -> Duration.ofHours(8);
            case "h12" -> Duration.ofHours(12);
            case "d1" -> Duration.ofDays(1);
            case "d3" -> Duration.ofDays(3);
            case "w1" -> Duration.ofDays(7);
            default -> {
                log.warn("Unknown interval '{}', defaulting to 15m", interval);
                yield Duration.ofMinutes(15);
            }
        };
    }
}

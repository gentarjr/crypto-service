package com.bot.testnet.crypto.service.risk;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Filter trading berdasarkan jam aktif (volume tinggi)
 *
 * Hindari trading saat low volume:
 * - Spread lebar
 * - False signal tinggi
 * - Slippage besar
 */
@Service
@Log4j2
public class TradingHoursService {

    @Value("${trading.hours.enabled:true}")
    private boolean enabled;

    @Value("${trading.hours.start-utc:8}")
    private int startHourUtc;

    @Value("${trading.hours.end-utc:21}")
    private int endHourUtc;

    /**
     * Apakah sekarang dalam jam trading aktif?
     */
    public boolean isWithinTradingHours() {
        if (!enabled) {
            return true;  // Kalau disabled, always allow
        }

        int currentHourUtc = ZonedDateTime.now(ZoneId.of("Asia/Jakarta")).getHour();
        boolean withinHours = currentHourUtc >= startHourUtc
                && currentHourUtc < endHourUtc;

        if (!withinHours) {
            log.info("Outside trading hours (UTC {}). Active: {}:00-{}:00 UTC",
                    currentHourUtc, startHourUtc, endHourUtc);
        }

        return withinHours;
    }

    /**
     * Info jam trading untuk logging
     */
    public String getTradingHoursInfo() {
        int currentHourUtc = ZonedDateTime.now(ZoneId.of("Asia/Jakarta")).getHour();
        return String.format("UTC %02d:xx | Active: %02d:00-%02d:00 UTC | Status: %s",
                currentHourUtc, startHourUtc, endHourUtc,
                isWithinTradingHours() ? "ACTIVE" : "CLOSED");
    }
}
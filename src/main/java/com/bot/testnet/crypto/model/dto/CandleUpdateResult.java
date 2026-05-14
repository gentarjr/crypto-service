package com.bot.testnet.crypto.model.dto;

public enum CandleUpdateResult {
    /**
     * Candle baru ditambahkan, dan sudah closed
     * → Trigger indicators calculation
     */
    NEW_CLOSED,

    /**
     * Candle baru ditambahkan, tapi masih live (closeTime > now)
     * → Tidak trigger indicators (data belum final)
     */
    NEW_LIVE,

    /**
     * Update existing candle (live), masih dalam fase live
     * → Tidak trigger indicators
     */
    UPDATED_STILL_LIVE,

    /**
     * Update existing candle (yang tadinya live), sekarang sudah closed
     * → Trigger indicators! (transisi live → closed)
     */
    UPDATED_NOW_CLOSED,

    /**
     * Candle lebih lama dari yang ada di cache, di-ignore
     * → Tidak ada action
     */
    OLDER_IGNORED;

    /**
     * Helper: apakah hasil ini harus trigger indicator calculation?
     */
    public boolean shouldTriggerIndicators() {
        return this == NEW_CLOSED || this == UPDATED_NOW_CLOSED;
    }
}

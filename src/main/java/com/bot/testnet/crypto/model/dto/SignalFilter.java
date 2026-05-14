package com.bot.testnet.crypto.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalFilter {

    private String filterName;  // nama filter (EMA_CROSS, VOLUME, RSI, ADX, dll)
    private boolean pass;       // apakah filter ini lolos?
    private String reason;      // penjelasan detail

    /**
     * Factory method untuk filter yang PASS
     */
    public static SignalFilter pass(String filterName, String reason) {
        return SignalFilter.builder()
                .filterName(filterName)
                .pass(true)
                .reason(reason)
                .build();
    }

    /**
     * Factory method untuk filter yang FAIL
     */
    public static SignalFilter fail(String filterName, String reason) {
        return SignalFilter.builder()
                .filterName(filterName)
                .pass(false)
                .reason(reason)
                .build();
    }
}

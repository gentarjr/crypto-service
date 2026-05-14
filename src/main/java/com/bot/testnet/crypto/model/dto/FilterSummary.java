package com.bot.testnet.crypto.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FilterSummary {

    private long total;
    private long passed;
    private long failed;
    private String progress;
    private Boolean readyToBuy;


}

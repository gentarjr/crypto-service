package com.bot.testnet.crypto.model.response;

import com.bot.testnet.crypto.model.dto.FilterSummary;
import com.bot.testnet.crypto.model.dto.Filters;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class GetSignalTradingResponse {

    private String decision;
    private String regime;
    private String activeStrategy;
    private Boolean isTransitionZone;
    private FilterSummary filterSummary;
    private List<Filters> filters;
    private List<String> whyNotBuying;
    private List<String> whatNeedsToHappen;
    private String timestamp;
}

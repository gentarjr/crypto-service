package com.bot.testnet.crypto.service.health;

import com.bot.testnet.crypto.model.dto.FilterSummary;
import com.bot.testnet.crypto.model.dto.Filters;
import com.bot.testnet.crypto.model.dto.SignalFilter;
import com.bot.testnet.crypto.model.response.GetIndicatorResponse;
import com.bot.testnet.crypto.model.response.GetSignalTradingResponse;
import com.bot.testnet.crypto.service.exchange.BbSignalService;
import com.bot.testnet.crypto.service.exchange.EmaSignalService;
import com.bot.testnet.crypto.service.indicator.IndicatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class GetSignalTradingService {

    private final IndicatorService indicatorService;
    private final EmaSignalService emaSignalService;
    private final BbSignalService bbSignalService;

    public GetSignalTradingResponse execute(){

        GetIndicatorResponse snapshot = indicatorService.calculate();
        if (snapshot == null) {
            return GetSignalTradingResponse.builder()
                    .decision("ERROR")
                    .activeStrategy("Cannot calculate indicators")
                    .build();
        }

        String regime = snapshot.getMarketRegime();
        boolean isTrending = regime.equals("TRENDING") || regime.equals("STRONG_TRENDING");
        boolean isRanging = regime.equals("RANGING");
        boolean isTransition = !isTrending && !isRanging;

        // ✅ Evaluate SEMUA filter (tidak short-circuit)
        List<SignalFilter> allFilters = isTransition ? List.of() :
                isTrending ? emaSignalService.evaluateAllFilters(snapshot) :
                        bbSignalService.evaluateAllFilters(snapshot);

        // Build response
        List<Filters> filterList = allFilters.stream()
                .map(f -> Filters.builder()
                        .filter(f.getFilterName())
                        .pass(f.isPass())
                        .status(f.isPass() ? "✅ PASS" : "❌ FAIL")
                        .reason(f.getReason())
                        .build()).toList();

        long passed = allFilters.stream().filter(SignalFilter::isPass).count();
        long failed = allFilters.stream().filter(f -> !f.isPass()).count();
        long total = allFilters.size();

        List<String> whyNot = allFilters.stream()
                .filter(f -> !f.isPass())
                .map(SignalFilter::getReason)
                .collect(Collectors.toList());

        List<String> needs = allFilters.stream()
                .filter(f -> !f.isPass())
                .map(f -> buildRequirement(f.getFilterName(), snapshot))
                .collect(Collectors.toList());

        boolean canBuy = !isTransition && failed == 0;

        return GetSignalTradingResponse.builder()
                .decision(canBuy ? "BUY ✅" : "HOLD ⏸️")
                .regime(regime)
                .activeStrategy(isTransition ? "NO_TRADE" :
                        isTrending ? "EMA_CROSSOVER" : "BB_MEAN_REVERSION")
                .isTransitionZone(isTransition)
                .filterSummary(FilterSummary.builder()
                        .total(total)
                        .passed(passed)
                        .failed(failed)
                        .progress(passed + "/" + total + " filters passed")
                        .readyToBuy(canBuy)
                        .build())
                .filters(filterList)
                .whyNotBuying(whyNot.isEmpty()
                        ? List.of(canBuy ? "All conditions met! 🎉" : "Transition zone")
                        : whyNot)
                .whatNeedsToHappen(needs.isEmpty()
                        ? List.of(canBuy ? "Nothing — ready to BUY!" : "Wait for clear regime")
                        : needs)
                .timestamp(LocalDateTime.now(ZoneId.of("Asia/Jakarta")).toString())
                .build();
    }


    private String buildRequirement(String filterName, GetIndicatorResponse snapshot) {
        return switch (filterName) {
            case "ADX_REGIME" ->
                    String.format("ADX %.2f needs to go ABOVE 25 (currently %s)",
                            snapshot.getAdx().doubleValue(),
                            snapshot.getAdx().doubleValue() < 25 ? "too low" : "ok");

            case "TREND_DIRECTION" ->
                    String.format("+DI %.2f needs to go ABOVE -DI %.2f (need bullish direction)",
                            snapshot.getPlusDI().doubleValue(),
                            snapshot.getMinusDI().doubleValue());

            case "EMA_CROSSOVER" ->
                    String.format("EMA9 (%.2f) needs to cross ABOVE EMA21 (%.2f) — gap: %.2f",
                            snapshot.getEmaFast().doubleValue(),
                            snapshot.getEmaSlow().doubleValue(),
                            snapshot.getEmaSlow().subtract(snapshot.getEmaFast()).doubleValue());

            case "VOLUME_SURGE" ->
                    String.format("Volume %.2fx needs to reach 1.5x — need %.2fx more",
                            snapshot.getVolumeRatio().doubleValue(),
                            1.5 - snapshot.getVolumeRatio().doubleValue());

            case "RSI_NOT_OVERBOUGHT" ->
                    String.format("RSI %.2f needs to go BELOW 70",
                            snapshot.getRsi().doubleValue());

            case "RSI_OVERSOLD" ->
                    String.format("RSI %.2f needs to go BELOW 30 (currently too high)",
                            snapshot.getRsi().doubleValue());

            case "BB_LOWER_TOUCH" ->
                    String.format("Price $%.2f needs to DROP to Lower BB $%.2f (gap: $%.2f)",
                            snapshot.getCurrentPrice().doubleValue(),
                            snapshot.getBbLower().doubleValue(),
                            snapshot.getCurrentPrice().subtract(snapshot.getBbLower()).doubleValue());

            case "BULLISH_CANDLE" ->
                    "Need bullish candle close above lower band";

            case "PRICE_EXTENSION" ->
                    String.format("Price $%.2f is too far from EMA21 $%.2f (max 3%%)",
                            snapshot.getCurrentPrice().doubleValue(),
                            snapshot.getEmaSlow().doubleValue());

            case "VOLATILITY_CB", "VOLATILITY_CIRCUIT_BREAKER" ->
                    String.format("ATR %.2f%% is EXTREME — wait for volatility to normalize",
                            snapshot.getAtrPercent().doubleValue());

            case "MTA_1H" ->
                    "1h trend needs to be BULLISH (price above EMA50 on 1h chart)";

            case "FALLING_KNIFE_PROTECTION" ->
                    String.format("%%B %.4f is too negative — price falling too fast, wait for stabilization",
                            snapshot.getBbPercentB().doubleValue());

            case "VOLUME_MINIMUM" ->
                    String.format("Volume %.2fx needs to be at least 0.5x",
                            snapshot.getVolumeRatio().doubleValue());

            case "EMA_UPTREND" ->
                    String.format("EMA9 (%.2f) needs to go ABOVE EMA21 (%.2f) — gap: %.2f",
                            snapshot.getEmaFast().doubleValue(),
                            snapshot.getEmaSlow().doubleValue(),
                            snapshot.getEmaSlow().subtract(snapshot.getEmaFast()).doubleValue());

            case "GOLDEN_CROSS" ->
                    String.format("EMA9 needs to CROSS ABOVE EMA21 for max score (currently uptrend only)");

            case "EMA_TREND_CONTINUATION" ->
                    "EMA uptrend continuing — golden cross would add +20 more points";

            case "ATR_EXTREME" ->
                    String.format("ATR %.2f%% is EXTREME — wait for volatility to normalize",
                            snapshot.getAtrPercent().doubleValue());

            default -> "Condition not yet met: " + filterName;
        };
    }
}

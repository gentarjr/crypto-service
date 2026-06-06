package com.bot.testnet.crypto.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class ConfigController {

    // ── General ──────────────────────────────────────────
    @Value("${trading.live.enabled:false}")        private boolean liveEnabled;
    @Value("${trading.pair.base:BNB}")              private String pairBase;
    @Value("${trading.pair.quote:USDT}")            private String pairQuote;

    // ── Risk ─────────────────────────────────────────────
    @Value("${trading.risk.modal:300}")                      private double modal;
    @Value("${trading.risk.risk-per-trade-percent:1.0}")     private double riskPerTrade;
    @Value("${trading.risk.max-daily-loss-percent:3.0}")     private double maxDailyLoss;
    @Value("${trading.risk.max-position-percent:90.0}")      private double maxPosition;
    @Value("${trading.risk.max-slippage-percent:0.3}")       private double maxSlippage;
    @Value("${trading.risk.max-consecutive-losses:3}")       private int    maxConsecutive;
    @Value("${trading.risk.cooldown-minutes:30}")            private int    cooldownMinutes;
    @Value("${trading.risk.bb-cooldown-minutes:30}")         private int    bbCooldownMinutes;
    @Value("${trading.risk.sl-atr-multiplier:1.5}")          private double slAtr;
    @Value("${trading.risk.tp-atr-multiplier:2.0}")          private double tpAtr;
    @Value("${trading.risk.trailing-atr-multiplier:1.5}")    private double trailingAtr;
    @Value("${trading.risk.timeout-hours:4}")                private int    timeoutHours;
    @Value("${trading.risk.timeout-profit-threshold:0.5}")   private double timeoutProfitThreshold;
    @Value("${trading.risk.partial-tp-enabled:true}")        private boolean partialTpEnabled;
    @Value("${trading.risk.partial-tp-ratio:0.5}")           private double partialTpRatio;

    // ── EMA strategy ─────────────────────────────────────
    @Value("${trading.strategy.ema.buy-score-threshold:60}")          private int    emaBuyScore;
    @Value("${trading.strategy.ema.strong-buy-score-threshold:80}")   private int    emaStrongScore;
    @Value("${trading.strategy.ema.rsi-max-threshold:70}")            private double emaRsiMax;

    // ── BB strategy ──────────────────────────────────────
    @Value("${trading.strategy.bb.buy-score-threshold:60}")           private int    bbBuyScore;
    @Value("${trading.strategy.bb.strong-buy-score-threshold:80}")    private int    bbStrongScore;
    @Value("${trading.strategy.bb.rsi-oversold-threshold:30}")        private double bbRsiOversold;
    @Value("${trading.strategy.bb.percent-b-min:-0.1}")               private double bbPercentBMin;
    @Value("${trading.strategy.bb.sl-atr-multiplier:0.5}")            private double bbSlAtr;
    @Value("${trading.strategy.bb.tp-atr-multiplier:1.0}")            private double bbTpAtr;

    // ── Regime / hours ───────────────────────────────────
    @Value("${trading.indicators.adx-ranging-threshold:20}")  private double adxRanging;
    @Value("${trading.indicators.adx-trending-threshold:25}")  private double adxTrending;
    @Value("${trading.hours.enabled:true}")  private boolean hoursEnabled;
    @Value("${trading.hours.start-utc:8}")   private int hoursStartUtc;
    @Value("${trading.hours.end-utc:21}")    private int hoursEndUtc;

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("liveEnabled", liveEnabled);
        m.put("pair", pairBase + "/" + pairQuote);
        m.put("modal", modal);

        m.put("riskPerTradePercent", riskPerTrade);
        m.put("maxDailyLossPercent", maxDailyLoss);
        m.put("maxPositionPercent", maxPosition);
        m.put("maxSlippagePercent", maxSlippage);
        m.put("maxConsecutiveLosses", maxConsecutive);
        m.put("cooldownMinutes", cooldownMinutes);
        m.put("bbCooldownMinutes", bbCooldownMinutes);

        m.put("slAtrMultiplier", slAtr);          // dipakai EMA (trading.risk.sl-atr)
        m.put("tpAtrMultiplier", tpAtr);          // dipakai EMA (trading.risk.tp-atr)
        m.put("trailingAtrMultiplier", trailingAtr);
        m.put("timeoutHours", timeoutHours);
        m.put("timeoutProfitThreshold", timeoutProfitThreshold);
        m.put("partialTpEnabled", partialTpEnabled);
        m.put("partialTpRatio", partialTpRatio);

        m.put("emaBuyScoreThreshold", emaBuyScore);
        m.put("emaStrongScoreThreshold", emaStrongScore);
        m.put("emaRsiMax", emaRsiMax);

        m.put("bbBuyScoreThreshold", bbBuyScore);
        m.put("bbStrongScoreThreshold", bbStrongScore);
        m.put("bbRsiOversold", bbRsiOversold);
        m.put("bbPercentBMin", bbPercentBMin);
        m.put("bbSlAtrMultiplier", bbSlAtr);
        m.put("bbTpAtrMultiplier", bbTpAtr);

        m.put("adxRangingThreshold", adxRanging);
        m.put("adxTrendingThreshold", adxTrending);
        m.put("tradingHoursEnabled", hoursEnabled);
        m.put("tradingHoursStartUtc", hoursStartUtc);
        m.put("tradingHoursEndUtc", hoursEndUtc);
        return m;
    }
}
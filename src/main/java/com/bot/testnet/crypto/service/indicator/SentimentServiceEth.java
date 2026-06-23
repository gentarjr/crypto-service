package com.bot.testnet.crypto.service.indicator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "trading.pair-eth.enabled", havingValue = "true")
@Log4j2
public class SentimentServiceEth {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${trading.sentiment-eth.enabled:false}")
    private boolean enabled;

    @Value("${trading.sentiment-eth.lunarcrush-api-key:}")
    private String apiKey;

    @Value("${trading.sentiment-eth.topic:eth}")
    private String topic;

    @Value("${trading.sentiment-eth.score-weight:15}")
    private int scoreWeight;

    @Value("${trading.sentiment-eth.cache-minutes:30}")
    private long cacheMinutes;

    @Value("${trading.sentiment-eth.lunarcrush-enabled:true}")
    private boolean lunarcrushEnabled;

    // ─── Cache LunarCrush ──────────────────────────────────
    private LunarCrushData cachedLC   = null;
    private Instant        lastFetchLC = Instant.EPOCH;

    // ─── Cache Fear & Greed ────────────────────────────────
    private int     cachedFNG      = 50;
    private String  cachedFNGLabel = "Neutral";
    private Instant lastFetchFNG   = Instant.EPOCH;

    // ─── Cache Volume Spike ────────────────────────────────
    private long previousInteractions = 0;

    private volatile boolean isFetching = false;
    private final Object lcLock  = new Object();
    private final Object fngLock = new Object();

    // ═══════════════════════════════════════════════════════
    // PUBLIC METHODS
    // ═══════════════════════════════════════════════════════

    public boolean isEnabled() {
        return enabled && !apiKey.isBlank();
    }

    public int getSentimentScore() {
        if (!isEnabled()) return 50;
        refreshAll();
        int lc  = (lunarcrushEnabled && cachedLC != null)
                ? cachedLC.weightedSentiment : 50;
        int fng = cachedFNG;
        return (int)(lc * 0.60 + fng * 0.40);
    }

    public String getSentimentLabel() {
        if (!isEnabled()) return "DISABLED";
        int s = getSentimentScore();
        if (s >= 75) return "VERY BULLISH";
        if (s >= 60) return "BULLISH";
        if (s >= 45) return "NEUTRAL";
        if (s >= 30) return "BEARISH";
        return "VERY BEARISH";
    }

    public String getTrend() {
        if (!isEnabled()) return "flat";
        refreshLunarCrush();
        return cachedLC != null ? cachedLC.trend : "flat";
    }

    public long getInteractions24h() {
        if (!isEnabled()) return 0;
        refreshLunarCrush();
        return cachedLC != null ? cachedLC.interactions24h : 0;
    }

    public int getFearGreedScore() { return cachedFNG; }
    public String getFearGreedLabel() { return cachedFNGLabel; }

    public Instant getLastFetchTime() { return lastFetchLC; }

    public boolean isSocialVolumeSpike() {
        if (!isEnabled() || cachedLC == null) return false;
        if (previousInteractions <= 0) return false;
        double change = (double)(cachedLC.interactions24h - previousInteractions)
                / previousInteractions * 100.0;
        return change > 50.0;
    }

    public double getSocialVolumeChangePercent() {
        if (!isEnabled() || cachedLC == null || previousInteractions <= 0) return 0;
        return (double)(cachedLC.interactions24h - previousInteractions)
                / previousInteractions * 100.0;
    }

    public int getSentimentBonusForEma() {
        if (!isEnabled()) return 0;
        int score  = getSentimentScore();
        String trend = getTrend();

        int bonus;
        if (score >= 70 && "up".equals(trend)) {
            bonus = scoreWeight;
        } else if (score >= 65) {
            bonus = (int)(scoreWeight * 0.75);
        } else if (score >= 55) {
            bonus = (int)(scoreWeight * 0.50);
        } else if (score >= 45) {
            bonus = 0;
        } else if (score >= 35) {
            bonus = -(int)(scoreWeight * 0.25);
        } else {
            bonus = -(int)(scoreWeight * 0.50);
        }

        if (isSocialVolumeSpike() && bonus >= 0) {
            bonus = Math.min(bonus + 5, scoreWeight + 5);
        }

        log.debug("📊 [ETH][EMA] Sentiment bonus: {} (score={}, trend={})",
                bonus, score, trend);
        return bonus;
    }

    public int getSentimentBonusForBb() {
        if (!isEnabled()) return 0;
        int score  = getSentimentScore();
        String trend = getTrend();

        int bonus;
        if (score < 25 && "down".equals(trend)) {
            bonus = scoreWeight;
        } else if (score < 35) {
            bonus = (int)(scoreWeight * 0.75);
        } else if (score < 45) {
            bonus = (int)(scoreWeight * 0.50);
        } else if (score < 60) {
            bonus = 0;
        } else if (score < 75) {
            bonus = -(int)(scoreWeight * 0.25);
        } else {
            bonus = -(int)(scoreWeight * 0.50);
        }

        log.debug("📊 [ETH][BB] Sentiment bonus: {} (score={}, trend={})",
                bonus, score, trend);
        return bonus;
    }

    public boolean isMarketTooFearfulForEma() {
        if (!isEnabled()) return false;
        return getSentimentScore() < 20 && "down".equals(getTrend());
    }

    public boolean isMarketTooGreedyForBb() {
        if (!isEnabled()) return false;
        return getSentimentScore() > 85 && "up".equals(getTrend());
    }

    // ═══════════════════════════════════════════════════════
    // PRIVATE: REFRESH
    // ═══════════════════════════════════════════════════════

    private void refreshAll() {
        refreshLunarCrush();
        refreshFearAndGreed();
    }

    private void refreshLunarCrush() {
        synchronized(lcLock) {
            if (!lunarcrushEnabled) {
                log.debug("[ETH] LunarCrush disabled, skipping");
                return;
            }

            if (cachedLC != null &&
                    Instant.now().isBefore(lastFetchLC.plusSeconds(cacheMinutes * 60))) {
                return;
            }
            isFetching = true;
            try {
                String url = "https://lunarcrush.com/api4/public/topic/" + topic + "/v1";
                HttpHeaders headers = new HttpHeaders();
                headers.set("Authorization", "Bearer " + apiKey);
                HttpEntity<String> entity = new HttpEntity<>(headers);

                ResponseEntity<LunarCrushResponse> resp = restTemplate.exchange(
                        url, HttpMethod.GET, entity, LunarCrushResponse.class);

                if (resp.getBody() != null && resp.getBody().getData() != null) {
                    LunarCrushTopicData d = resp.getBody().getData();

                    if (cachedLC != null) {
                        previousInteractions = cachedLC.interactions24h;
                    }

                    LunarCrushData newData = new LunarCrushData();
                    newData.trend = d.getTrend() != null ? d.getTrend() : "flat";
                    newData.interactions24h = d.getInteractions24h();
                    newData.numPosts = d.getNumPosts();
                    newData.numContributors = d.getNumContributors();

                    newData.weightedSentiment = calcWeightedSentiment(
                            d.getTypesSentiment(),
                            d.getTypesInteractions());

                    cachedLC = newData;
                    lastFetchLC = ZonedDateTime.now(ZoneOffset.UTC).toInstant();

                    double spikeChg = getSocialVolumeChangePercent();
                    log.info("📊 [LUNARCRUSH][ETH]: sentiment={}, trend={}, interactions={}, spike={}%",
                            newData.weightedSentiment, newData.trend,
                            newData.interactions24h,
                            String.format("%.1f", spikeChg));
                }
            } finally {
                isFetching = false;
            }
        }
    }

    private void refreshFearAndGreed() {
        synchronized(fngLock) {
            if (Instant.now().isBefore(lastFetchFNG.plusSeconds(6 * 3600))) return;
            try {
                FearGreedResponse resp = restTemplate.getForObject(
                        "https://api.alternative.me/fng/?limit=1",
                        FearGreedResponse.class);

                if (resp != null && resp.getData() != null && !resp.getData().isEmpty()) {
                    cachedFNG      = Integer.parseInt(resp.getData().get(0).getValue());
                    cachedFNGLabel = resp.getData().get(0).getValueClassification();
                    lastFetchFNG   = ZonedDateTime.now(ZoneOffset.UTC).toInstant();
                    log.info("📊 [FEAR&GREED][ETH] Score: {} ({})", cachedFNG, cachedFNGLabel);
                }
            } catch (Exception e) {
                log.warn("⚠️ [ETH] Fear&Greed fetch failed: {} — using cache", e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // PRIVATE: Platform-Specific Weighted Sentiment
    // ═══════════════════════════════════════════════════════

    private int calcWeightedSentiment(
            Map<String, Integer> typesSentiment,
            Map<String, Long>    typesInteractions) {

        if (typesSentiment == null || typesSentiment.isEmpty()) return 50;

        long totalInteractions = 0;
        if (typesInteractions != null) {
            totalInteractions = typesInteractions.values().stream()
                    .mapToLong(Long::longValue).sum();
        }

        if (totalInteractions == 0 || typesInteractions == null) {
            return (int) typesSentiment.values().stream()
                    .mapToInt(Integer::intValue).average().orElse(50);
        }

        double weightedSum  = 0.0;
        double totalWeight  = 0.0;

        for (Map.Entry<String, Integer> entry : typesSentiment.entrySet()) {
            String platform      = entry.getKey();
            int    sentimentScore = entry.getValue();
            long   interactions  = typesInteractions.getOrDefault(platform, 0L);

            if (interactions > 0) {
                double weight = (double) interactions / totalInteractions;
                weightedSum  += sentimentScore * weight;
                totalWeight  += weight;

                log.debug("  [ETH] Platform {}: sentiment={}, interactions={}, weight={}",
                        platform, sentimentScore, interactions,
                        String.format("%.2f", weight));
            }
        }

        int result = totalWeight > 0
                ? (int)(weightedSum / totalWeight)
                : (int) typesSentiment.values().stream()
                .mapToInt(Integer::intValue).average().orElse(50);

        log.debug("📊 [ETH] Weighted sentiment: {} (platforms: {})",
                result, typesSentiment.keySet());
        return result;
    }

    // ═══════════════════════════════════════════════════════
    // INNER CLASSES
    // ═══════════════════════════════════════════════════════

    private static class LunarCrushData {
        int    weightedSentiment;
        String trend;
        long   interactions24h;
        long   numPosts;
        long   numContributors;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LunarCrushResponse {
        private LunarCrushTopicData data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LunarCrushTopicData {
        private String trend;

        @JsonProperty("interactions_24h")
        private long interactions24h;

        @JsonProperty("num_posts")
        private long numPosts;

        @JsonProperty("num_contributors")
        private long numContributors;

        @JsonProperty("types_sentiment")
        private Map<String, Integer> typesSentiment;

        @JsonProperty("types_interactions")
        private Map<String, Long> typesInteractions;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FearGreedResponse {
        private List<FearGreedData> data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FearGreedData {
        private String value;
        @JsonProperty("value_classification")
        private String valueClassification;
    }
}
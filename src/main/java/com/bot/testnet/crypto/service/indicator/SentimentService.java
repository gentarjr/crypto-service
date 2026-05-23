package com.bot.testnet.crypto.service.indicator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

@Service
@Log4j2
public class SentimentService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${trading.sentiment.enabled:false}")
    private boolean enabled;

    @Value("${trading.sentiment.lunarcrush-api-key:}")
    private String apiKey;

    @Value("${trading.sentiment.topic:bnb}")
    private String topic;

    @Value("${trading.sentiment.score-weight:15}")
    private int scoreWeight;

    @Value("${trading.sentiment.cache-minutes:30}")
    private long cacheMinutes;

    @Value("${trading.sentiment.lunarcrush-enabled:true}")
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

    // ═══════════════════════════════════════════════════════
    // PUBLIC METHODS
    // ═══════════════════════════════════════════════════════

    public boolean isEnabled() {
        return enabled && !apiKey.isBlank();
    }

    /**
     * Combined sentiment score 0-100
     * LunarCrush (60%) + Fear&Greed (40%)
     */
    public int getSentimentScore() {
        if (!isEnabled()) return 50;
        refreshAll(); // refreshFearAndGreed tetap jalan
        int lc  = (lunarcrushEnabled && cachedLC != null)
                ? cachedLC.weightedSentiment : 50; // neutral kalau disabled
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

    /**
     * Cek apakah ada social volume spike
     * Spike = interactions naik > 50% dari sebelumnya
     */
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

    /**
     * Bonus poin untuk EMA strategy
     * EMA = trend following → suka market BULLISH
     */
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

        // Volume spike bonus (+5 extra)
        if (isSocialVolumeSpike() && bonus >= 0) {
            bonus = Math.min(bonus + 5, scoreWeight + 5);
        }

        log.debug("📊 [EMA] Sentiment bonus: {} (score={}, trend={})",
                bonus, score, trend);
        return bonus;
    }

    /**
     * Bonus poin untuk BB strategy
     * BB = mean reversion → suka market FEARFUL (oversold bounce)
     */
    public int getSentimentBonusForBb() {
        if (!isEnabled()) return 0;
        int score  = getSentimentScore();
        String trend = getTrend();

        int bonus;
        if (score < 25 && "down".equals(trend)) {
            // Extreme fear + downtrend = PERFECT BB reversal setup
            bonus = scoreWeight;
        } else if (score < 35) {
            // Very fearful = good BB entry
            bonus = (int)(scoreWeight * 0.75);
        } else if (score < 45) {
            // Slightly fearful = ok BB entry
            bonus = (int)(scoreWeight * 0.50);
        } else if (score < 60) {
            // Neutral = no bonus no penalty
            bonus = 0;
        } else if (score < 75) {
            // Bullish = caution for BB (market not oversold)
            bonus = -(int)(scoreWeight * 0.25);
        } else {
            // Extreme greed = bad BB entry (market overbought)
            bonus = -(int)(scoreWeight * 0.50);
        }

        log.debug("📊 [BB] Sentiment bonus: {} (score={}, trend={})",
                bonus, score, trend);
        return bonus;
    }

    /**
     * Hard block untuk EMA: skip kalau market sangat bearish
     */
    public boolean isMarketTooFearfulForEma() {
        if (!isEnabled()) return false;
        return getSentimentScore() < 20 && "down".equals(getTrend());
    }

    /**
     * Hard block untuk BB: skip kalau market terlalu euphoric
     * (tidak ada bounce kalau market sedang greed)
     */
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
        if (!lunarcrushEnabled) {
            log.debug("LunarCrush disabled, skipping");
            return;
        }

        if (cachedLC != null &&
                ZonedDateTime.now(ZoneId.of("Asia/Jakarta")).toInstant().isBefore(lastFetchLC.plusSeconds(cacheMinutes * 60))) {
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

                // Simpan previous interactions untuk spike detection
                if (cachedLC != null) {
                    previousInteractions = cachedLC.interactions24h;
                }

                LunarCrushData newData = new LunarCrushData();
                newData.trend           = d.getTrend() != null ? d.getTrend() : "flat";
                newData.interactions24h = d.getInteractions24h();
                newData.numPosts        = d.getNumPosts();
                newData.numContributors = d.getNumContributors();

                // Platform-specific weighted sentiment
                newData.weightedSentiment = calcWeightedSentiment(
                        d.getTypesSentiment(),
                        d.getTypesInteractions());

                cachedLC    = newData;
                lastFetchLC = ZonedDateTime.now(ZoneId.of("Asia/Jakarta")).toInstant();

                double spikeChg = getSocialVolumeChangePercent();
                log.info("📊 [LUNARCRUSH] BNB: sentiment={}, trend={}, interactions={}, spike={}%",
                        newData.weightedSentiment, newData.trend,
                        newData.interactions24h,
                        String.format("%.1f", spikeChg));
            }
        } finally {
            isFetching = false;
        }
    }

    private void refreshFearAndGreed() {
        // Update FNG tiap 6 jam (update harian, tidak perlu sering)
        if (ZonedDateTime.now(ZoneId.of("Asia/Jakarta")).toInstant().isBefore(lastFetchFNG.plusSeconds(6 * 3600))) return;
        try {
            FearGreedResponse resp = restTemplate.getForObject(
                    "https://api.alternative.me/fng/?limit=1",
                    FearGreedResponse.class);

            if (resp != null && resp.getData() != null && !resp.getData().isEmpty()) {
                cachedFNG      = Integer.parseInt(resp.getData().get(0).getValue());
                cachedFNGLabel = resp.getData().get(0).getValueClassification();
                lastFetchFNG   = ZonedDateTime.now(ZoneId.of("Asia/Jakarta")).toInstant();
                log.info("📊 [FEAR&GREED] Score: {} ({})", cachedFNG, cachedFNGLabel);
            }
        } catch (Exception e) {
            log.warn("⚠️ Fear&Greed fetch failed: {} — using cache", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════
    // PRIVATE: Platform-Specific Weighted Sentiment
    // ═══════════════════════════════════════════════════════

    /**
     * Hitung weighted sentiment berdasarkan volume aktual tiap platform
     * Platform dengan lebih banyak interaction = lebih berpengaruh
     */
    private int calcWeightedSentiment(
            Map<String, Integer> typesSentiment,
            Map<String, Long>    typesInteractions) {

        if (typesSentiment == null || typesSentiment.isEmpty()) return 50;

        // Total semua interactions
        long totalInteractions = 0;
        if (typesInteractions != null) {
            totalInteractions = typesInteractions.values().stream()
                    .mapToLong(Long::longValue).sum();
        }

        if (totalInteractions == 0 || typesInteractions == null) {
            // Fallback: simple average
            return (int) typesSentiment.values().stream()
                    .mapToInt(Integer::intValue).average().orElse(50);
        }

        // Weighted sum berdasarkan proporsi interactions
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

                log.debug("  Platform {}: sentiment={}, interactions={}, weight={}",
                        platform, sentimentScore, interactions,
                        String.format("%.2f", weight));
            }
        }

        int result = totalWeight > 0
                ? (int)(weightedSum / totalWeight)
                : (int) typesSentiment.values().stream()
                .mapToInt(Integer::intValue).average().orElse(50);

        log.debug("📊 Weighted sentiment: {} (platforms: {})",
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

    // LunarCrush API v4 Response
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

    // Fear & Greed API Response
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
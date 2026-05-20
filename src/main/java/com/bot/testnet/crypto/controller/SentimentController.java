package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.service.indicator.SentimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class SentimentController {

    private final SentimentService sentimentService;

    @GetMapping("/sentiment/status")
    public Map<String, Object> getSentimentStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", sentimentService.isEnabled());
        result.put("combinedScore", sentimentService.getSentimentScore());
        result.put("label", sentimentService.getSentimentLabel());
        result.put("trend", sentimentService.getTrend());
        result.put("fearGreedScore", sentimentService.getFearGreedScore());
        result.put("fearGreedLabel", sentimentService.getFearGreedLabel());
        result.put("interactions24h", sentimentService.getInteractions24h());
        result.put("socialVolumeSpike", sentimentService.isSocialVolumeSpike());
        result.put("spikeChangePercent",
                String.format("%.1f%%", sentimentService.getSocialVolumeChangePercent()));
        result.put("emaBonusPreview", sentimentService.getSentimentBonusForEma());
        result.put("bbBonusPreview", sentimentService.getSentimentBonusForBb());
        result.put("lastUpdated", sentimentService.getLastFetchTime());
        return result;
    }
}
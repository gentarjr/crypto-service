package com.bot.testnet.crypto.service.scheduler;

import com.bot.testnet.crypto.service.screener.ScreenerValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScreenerValidationScheduler {

    private final ScreenerValidationService screenerValidationService;

    // Cek tiap 30 menit — cukup untuk window 24h/48h, tidak perlu lebih sering.
    @Scheduled(fixedRate = 30 * 60 * 1000, scheduler = "screenerTaskScheduler")
    public void runValidationCheck() {
        screenerValidationService.checkPending24h();
        screenerValidationService.checkPending48h();
    }
}
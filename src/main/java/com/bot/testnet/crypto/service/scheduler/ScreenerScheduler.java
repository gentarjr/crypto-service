package com.bot.testnet.crypto.service.scheduler;

import com.bot.testnet.crypto.service.screener.CoinScreenerService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScreenerScheduler {

    private final CoinScreenerService coinScreenerService;

    // scheduler = "screenerTaskScheduler" WAJIB eksplisit — kalau tidak,
    // Spring pakai TaskScheduler bean pertama yang ketemu (bisa saja bean
    // taskScheduler() yang sudah dipakai 11 scheduler trading existing).
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Jakarta", scheduler = "screenerTaskScheduler")
    public void runScreening() {
        coinScreenerService.runScreeningCycle();
    }
}
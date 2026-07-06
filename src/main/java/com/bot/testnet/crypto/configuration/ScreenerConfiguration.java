package com.bot.testnet.crypto.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

/**
 * Konfigurasi khusus fitur Screener.
 * TERPISAH dari TaskScheduler utama (CryptoServiceApplication) dan dari
 * Exchange bean (BinanceConfiguration/XChange) yang dipakai OrderExecutorService.
 * Tujuan: screener tidak boleh rebutan thread/koneksi dengan bot trading live.
 */
@Configuration
public class ScreenerConfiguration {

    @Bean(name = "screenerTaskScheduler")
    public TaskScheduler screenerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2); // cukup untuk 1 job screener, tidak numpang di 5 thread yang sudah ada
        scheduler.setThreadNamePrefix("screener-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Bean(name = "binancePublicRestClient")
    public RestClient binancePublicRestClient() {
        // Endpoint /ticker/24hr itu public, tidak butuh API key.
        // Sengaja tidak lewat Exchange (XChange) bean supaya tidak share
        // koneksi dengan OrderExecutorService/PriceMonitorService.
        // SimpleClientHttpRequestFactory instance baru = connection terpisah
        // dari client HTTP internal XChange.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000); // bulk response bisa besar (~2000 symbol), kasih ruang

        return RestClient.builder()
                .baseUrl("https://api.binance.com")
                .requestFactory(factory)
                .build();
    }
}
package com.bot.testnet.crypto.configuration;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.binance.BinanceExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Log4j2
@Getter
public class BinanceConfiguration {

    @Value("${exchange.binance.api-key}")
    private String apiKey;

    @Value("${exchange.binance.secret-key}")
    private String secretKey;

    @Value("${exchange.binance.testnet}")
    private boolean testnet;

    @Bean
    public Exchange verifyConfig() {
        log.info("🔧 Initializing Binance Exchange (testnet={})", testnet);

        ExchangeSpecification spec = new BinanceExchange().getDefaultExchangeSpecification();
        spec.setApiKey(apiKey);
        spec.setSecretKey(secretKey);

        if (testnet) {
            spec.setExchangeSpecificParametersItem("Use_Sandbox", true);
            spec.setSslUri("https://testnet.binance.vision");
            spec.setHost("testnet.binance.vision");
            log.warn("⚠️  Using Binance TESTNET — no real money");
        } else {
            spec.setSslUri("https://api.binance.com");
            spec.setHost("api.binance.com");
            log.warn("🔴 Using Binance MAINNET — REAL MONEY!");
        }

        Exchange exchange = ExchangeFactory.INSTANCE.createExchange(spec);
        log.info("✅ Binance Exchange initialized");

        return exchange;
    }
}
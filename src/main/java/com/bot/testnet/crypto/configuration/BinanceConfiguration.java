package com.bot.testnet.crypto.configuration;

import lombok.Getter;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.binance.BinanceExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
public class BinanceConfiguration {

    @Value("${exchange.binance.api-key}")
    private String apiKey;

    @Value("${exchange.binance.secret-key}")
    private String secretKey;

    @Value("${exchange.binance.testnet}")
    private boolean testnet;

    @Bean
    public Exchange binanceExchange() {
        ExchangeSpecification spec = new BinanceExchange().getDefaultExchangeSpecification();
        spec.setApiKey(apiKey);
        spec.setSecretKey(secretKey);

        // PENTING: Aktifkan testnet mode
        spec.setExchangeSpecificParametersItem("Use_Sandbox", testnet);

        return ExchangeFactory.INSTANCE.createExchange(spec);
    }
}

package com.bot.testnet.crypto.model.dto;

public enum StrategyType {
    EMA_CROSSOVER,          // Strategi 1: trending market
    BB_MEAN_REVERSION,      // Strategi 2: ranging market
    NO_TRADE                // transition zone / tidak memenuhi syarat
}

package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.model.entity.CoinCandidate;
import com.bot.testnet.crypto.repository.CoinCandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ScreenerController {

    private final CoinCandidateRepository coinCandidateRepository;

    @GetMapping("/api/screener/top-candidates")
    public List<CoinCandidate> getTopCandidates() {
        return coinCandidateRepository.findAllOrderedByRank();
    }
}
package com.bot.testnet.crypto.repository;

import com.bot.testnet.crypto.model.entity.CoinCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CoinCandidateRepository extends JpaRepository<CoinCandidate, String> {

    @Query("SELECT c FROM CoinCandidate c ORDER BY c.rankPosition ASC")
    List<CoinCandidate> findAllOrderedByRank();
}
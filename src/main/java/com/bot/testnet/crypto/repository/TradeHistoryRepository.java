package com.bot.testnet.crypto.repository;

import com.bot.testnet.crypto.model.entity.TradeHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Repository
public interface TradeHistoryRepository extends JpaRepository<TradeHistory, String> {

    Page<TradeHistory> findAllByOrderByCloseTimeDesc(Pageable pageable);

    List<TradeHistory> findByCloseTimeAfterOrderByCloseTimeDesc(Instant since);

    @Query("SELECT SUM(t.pnlAfterFee) FROM TradeHistory t WHERE t.closeTime >= :since")
    BigDecimal sumPnlSince(Instant since);

    @Query("SELECT COUNT(t) FROM TradeHistory t WHERE t.pnlAfterFee > 0 AND t.closeTime >= :since")
    long countWinsSince(Instant since);

    @Query("SELECT COUNT(t) FROM TradeHistory t WHERE t.closeTime >= :since")
    long countTotalSince(Instant since);

    @Query("SELECT t FROM TradeHistory t WHERE COALESCE(t.pair, 'BNB') = :pair ORDER BY t.closeTime DESC")
    Page<TradeHistory> findByPairOrderByCloseTimeDesc(String pair, Pageable pageable);

    @Query("SELECT SUM(t.pnlAfterFee) FROM TradeHistory t WHERE t.closeTime >= :since AND COALESCE(t.pair, 'BNB') = :pair")
    BigDecimal sumPnlSinceByPair(Instant since, String pair);

    @Query("SELECT COUNT(t) FROM TradeHistory t WHERE t.pnlAfterFee > 0 AND t.closeTime >= :since AND COALESCE(t.pair, 'BNB') = :pair")
    long countWinsSinceByPair(Instant since, String pair);

    @Query("SELECT COUNT(t) FROM TradeHistory t WHERE t.closeTime >= :since AND COALESCE(t.pair, 'BNB') = :pair")
    long countTotalSinceByPair(Instant since, String pair);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM TradeHistory t WHERE COALESCE(t.pair, 'BNB') = :pair")
    void deleteByPair(String pair);
}
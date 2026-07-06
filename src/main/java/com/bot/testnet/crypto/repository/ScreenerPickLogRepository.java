package com.bot.testnet.crypto.repository;

import com.bot.testnet.crypto.model.entity.ScreenerPickLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ScreenerPickLogRepository extends JpaRepository<ScreenerPickLog, Long> {

    @Query("SELECT s FROM ScreenerPickLog s WHERE s.symbol = :symbol AND s.pickedAt > :since ORDER BY s.pickedAt DESC")
    List<ScreenerPickLog> findRecentBySymbol(@Param("symbol") String symbol, @Param("since") Instant since);

    List<ScreenerPickLog> findByChecked24hFalseAndPickedAtBefore(Instant cutoff);

    List<ScreenerPickLog> findByChecked48hFalseAndPickedAtBefore(Instant cutoff);

    List<ScreenerPickLog> findAllByOrderByPickedAtDesc();
}
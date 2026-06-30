package com.bot.testnet.crypto.repository;

import com.bot.testnet.crypto.model.entity.EquityTrackingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EquityTrackingRepository extends JpaRepository<EquityTrackingEntity, String> {
}
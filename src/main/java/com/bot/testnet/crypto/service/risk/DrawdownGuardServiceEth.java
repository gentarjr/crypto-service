package com.bot.testnet.crypto.service.risk;

import com.bot.testnet.crypto.model.entity.EquityTrackingEntity;
import com.bot.testnet.crypto.repository.EquityTrackingRepository;
import com.bot.testnet.crypto.service.TelegramNotificationService;
import com.bot.testnet.crypto.service.exchange.BalanceService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Log4j2
public class DrawdownGuardServiceEth {

    private static final String PAIR_SCOPE = "ETH";

    private final EquityTrackingRepository repository;
    private final TelegramNotificationService telegramNotificationService;
    private final BalanceService balanceService;

    @Value("${trading.risk.max-drawdown-percent:20.0}")
    private double maxDrawdownPercent;

    private final ReentrantLock lock = new ReentrantLock();

    @PostConstruct
    public void initializeBaseline() {
        lock.lock();
        try {
            if (repository.existsById(PAIR_SCOPE)) {
                log.info("[{}] Equity tracking record already exists, skip baseline init", PAIR_SCOPE);
                return;
            }

            balanceService.getTotalCapitalSafe().ifPresentOrElse(
                    currentBalance -> {
                        EquityTrackingEntity entity = initEntity(currentBalance);
                        repository.save(entity);
                        log.info("[{}] Drawdown baseline initialized from current balance: ${}", PAIR_SCOPE, currentBalance);
                    },
                    () -> log.error("[{}] FAILED to initialize drawdown baseline — could not fetch balance at startup. " +
                            "Equity tracking will init on first trade close instead (less accurate).", PAIR_SCOPE)
            );
        } finally {
            lock.unlock();
        }
    }

    public void updateEquity(BigDecimal currentEquity) {
        lock.lock();
        try {
            EquityTrackingEntity entity = repository.findById(PAIR_SCOPE)
                    .orElseGet(() -> initEntity(currentEquity));

            entity.setCurrentEquity(currentEquity);
            entity.setLastUpdated(Instant.now());

            if (currentEquity.compareTo(entity.getPeakEquity()) > 0) {
                entity.setPeakEquity(currentEquity);
                entity.setPeakTimestamp(Instant.now());
            }

            BigDecimal maxDrawdownPct = BigDecimal.valueOf(maxDrawdownPercent);
            BigDecimal drawdownPct = entity.getPeakEquity().subtract(currentEquity)
                    .divide(entity.getPeakEquity(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            if (drawdownPct.compareTo(maxDrawdownPct) >= 0 && !entity.isDrawdownBreached()) {
                entity.setDrawdownBreached(true);
                entity.setBreachTimestamp(Instant.now());
                entity.setBreachEquity(currentEquity);
                telegramNotificationService.sendMessage(
                        "🚨 [" + PAIR_SCOPE + "] MAX DRAWDOWN BREACH",
                        String.format("Drawdown: %.2f%% (limit: %.2f%%)\nPeak: %s\nCurrent: %s\nBot STOP buka posisi baru. Resume via /admin/risk/resume/%s",
                                drawdownPct, maxDrawdownPercent, entity.getPeakEquity(), currentEquity, PAIR_SCOPE));
                log.error("[{}] Drawdown breach triggered: {}%", PAIR_SCOPE, drawdownPct);
            }

            repository.save(entity);
        } finally {
            lock.unlock();
        }
    }

    public boolean isBreached() {
        return repository.findById(PAIR_SCOPE)
                .map(EquityTrackingEntity::isDrawdownBreached)
                .orElse(false);
    }

    public void manualResume() {
        lock.lock();
        try {
            EquityTrackingEntity entity = repository.findById(PAIR_SCOPE)
                    .orElseThrow(() -> new IllegalStateException("No equity tracking record for " + PAIR_SCOPE));
            entity.setDrawdownBreached(false);
            entity.setLastUpdated(Instant.now());
            repository.save(entity);
            log.info("[{}] Drawdown guard manually resumed. Peak={}, Current={}", PAIR_SCOPE,
                    entity.getPeakEquity(), entity.getCurrentEquity());
        } finally {
            lock.unlock();
        }
    }

    public EquityTrackingEntity getStatus() {
        return repository.findById(PAIR_SCOPE).orElse(null);
    }

    private EquityTrackingEntity initEntity(BigDecimal currentEquity) {
        EquityTrackingEntity entity = new EquityTrackingEntity();
        entity.setPairScope(PAIR_SCOPE);
        entity.setPeakEquity(currentEquity);
        entity.setCurrentEquity(currentEquity);
        entity.setPeakTimestamp(Instant.now());
        entity.setDrawdownBreached(false);
        entity.setLastUpdated(Instant.now());
        return entity;
    }
}
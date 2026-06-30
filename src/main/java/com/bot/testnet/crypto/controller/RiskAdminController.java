package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.model.entity.EquityTrackingEntity;
import com.bot.testnet.crypto.service.risk.DrawdownGuardService;
import com.bot.testnet.crypto.service.risk.DrawdownGuardServiceEth;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequestMapping("/admin/risk")
@RequiredArgsConstructor
public class RiskAdminController {

    // Hasil dari: echo -n "password_lo" | sha256sum — ganti dengan hash punya lo
    private static final String SECRET_HASH = "4bed4b55e2839d5d819b4821603f83e79585f2b41dc441bf9b6c8d900123a8c6";

    private final DrawdownGuardService drawdownGuardService;
    private final DrawdownGuardServiceEth drawdownGuardServiceEth;

    @GetMapping("/status")
    public ResponseEntity<?> status(@RequestParam(required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(401).body("unauthorized");
        EquityTrackingEntity bnb = drawdownGuardService.getStatus();
        EquityTrackingEntity eth = drawdownGuardServiceEth.getStatus();
        return ResponseEntity.ok(Map.of("BNB", bnb, "ETH", eth));
    }

    @PostMapping("/resume/{pair}")
    public ResponseEntity<?> resume(@PathVariable String pair, @RequestParam(required = false) String key) {
        if (!isAuthorized(key)) return ResponseEntity.status(401).body("unauthorized");
        switch (pair.toUpperCase()) {
            case "BNB" -> drawdownGuardService.manualResume();
            case "ETH" -> drawdownGuardServiceEth.manualResume();
            default -> {
                return ResponseEntity.badRequest().body("Unknown pair: " + pair);
            }
        }
        return ResponseEntity.ok("Resumed: " + pair.toUpperCase());
    }

    private boolean isAuthorized(String key) {
        if (key == null) return false;
        return MessageDigest.isEqual(
                sha256(key).getBytes(StandardCharsets.UTF_8),
                SECRET_HASH.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
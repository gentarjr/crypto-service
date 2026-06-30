package com.bot.testnet.crypto.controller;

import com.bot.testnet.crypto.model.entity.EquityTrackingEntity;
import com.bot.testnet.crypto.service.risk.DrawdownGuardSecretService;
import com.bot.testnet.crypto.service.risk.DrawdownGuardService;
import com.bot.testnet.crypto.service.risk.DrawdownGuardServiceEth;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/risk")
@RequiredArgsConstructor
public class RiskAdminController {

    private static final String SECRET = "316ac493977492520294f48aa05b0f559e6bd692d75ba0b62b3deef5e7b59c45";

    private final DrawdownGuardService drawdownGuardService;
    private final DrawdownGuardServiceEth drawdownGuardServiceEth;
    private final DrawdownGuardSecretService drawdownGuardSecretService;

    @GetMapping("/secret")
    public Map<String, String> secret(@RequestParam(required = false) String secret){
        return drawdownGuardSecretService.getSecret(secret);
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(@RequestParam(required = false) String key) {
        if (!SECRET.equalsIgnoreCase(key)) return ResponseEntity.status(401).body("unauthorized");
        EquityTrackingEntity bnb = drawdownGuardService.getStatus();
        EquityTrackingEntity eth = drawdownGuardServiceEth.getStatus();
        return ResponseEntity.ok(Map.of("BNB", bnb, "ETH", eth));
    }

    @PostMapping("/resume/{pair}")
    public ResponseEntity<?> resume(@PathVariable String pair, @RequestParam(required = false) String key) {
        if (!SECRET.equalsIgnoreCase(key)) return ResponseEntity.status(401).body("unauthorized");
        switch (pair.toUpperCase()) {
            case "BNB" -> drawdownGuardService.manualResume();
            case "ETH" -> drawdownGuardServiceEth.manualResume();
            default -> {
                return ResponseEntity.badRequest().body("Unknown pair: " + pair);
            }
        }
        return ResponseEntity.ok("Resumed: " + pair.toUpperCase());
    }
}
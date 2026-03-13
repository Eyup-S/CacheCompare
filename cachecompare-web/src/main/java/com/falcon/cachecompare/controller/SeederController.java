package com.falcon.cachecompare.controller;

import com.falcon.cachecompare.service.DataSeederService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class SeederController {

    private final DataSeederService dataSeederService;

    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seed() {
        int inserted = dataSeederService.seed();
        String message = inserted > 0
                ? "Seeded successfully: " + inserted + " products inserted."
                : "Already seeded, skipped.";
        return ResponseEntity.ok(Map.of("inserted", inserted, "message", message));
    }
}

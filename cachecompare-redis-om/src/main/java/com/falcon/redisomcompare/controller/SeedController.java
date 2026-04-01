package com.falcon.redisomcompare.controller;

import com.falcon.redisomcompare.service.DataSeederService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class SeedController {

    private final DataSeederService dataSeederService;

    /**
     * PostgreSQL'e ürün ekler.
     * Örnek: POST /seed?count=1000 veya POST /seed?count=200000
     */
    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seed(
            @RequestParam(defaultValue = "1000") int count) {
        int inserted = dataSeederService.seed(count);
        return ResponseEntity.ok(Map.of(
                "targetCount", count,
                "inserted", inserted,
                "message", inserted == 0 ? "Already seeded" : "Seeded successfully"
        ));
    }
}

package com.payment.timesync.controller;

import com.payment.common.model.HybridTimestamp;
import com.payment.timesync.config.ClockConfig;
import com.payment.timesync.service.LogicalClock;
import com.payment.timesync.service.NetworkTimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

/**
 * REST endpoints for time synchronization operations.
 */
@RestController
@RequiredArgsConstructor
public class ClockController {

    private final NetworkTimeService networkTimeService;
    private final LogicalClock logicalClock;
    private final ClockConfig config;

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "nodeId", config.getNodeId()));
    }

    @GetMapping("/time/status")
    public ResponseEntity<Map<String, Object>> getTimeStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("nodeId", config.getNodeId());
        status.put("ntpOffset", networkTimeService.getOffset());
        status.put("syncSuccessful", networkTimeService.isSyncSuccessful());
        status.put("lamportTime", logicalClock.getTime());
        status.put("correctedTime", networkTimeService.getCorrectedTime());
        return ResponseEntity.ok(status);
    }

    @GetMapping("/time/now")
    public ResponseEntity<HybridTimestamp> getCurrentTime() {
        long logicalTime = logicalClock.tick();
        HybridTimestamp timestamp = HybridTimestamp.nowWithOffset(networkTimeService.getOffset(), logicalTime);
        return ResponseEntity.ok(timestamp);
    }

    @PostMapping("/time/event")
    public ResponseEntity<Map<String, Object>> recordEvent(@RequestBody(required = false) Map<String, Object> eventData) {
        long logicalTime;
        if (eventData != null && eventData.containsKey("receivedLogicalTime")) {
            long receivedTime = ((Number) eventData.get("receivedLogicalTime")).longValue();
            logicalTime = logicalClock.receive(receivedTime);
        } else {
            logicalTime = logicalClock.tick();
        }

        HybridTimestamp timestamp = HybridTimestamp.nowWithOffset(networkTimeService.getOffset(), logicalTime);
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", timestamp);
        response.put("nodeId", config.getNodeId());
        return ResponseEntity.ok(response);
    }
}

package com.payment.fault.controller;

import com.payment.common.model.PaymentTransaction;
import com.payment.fault.config.FaultConfig;
import com.payment.fault.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

/**
 * REST endpoints for cluster health monitoring and fault tolerance.
 */
@RestController
@RequiredArgsConstructor
public class ClusterHealthController {

    private final NodeHealthChecker healthChecker;
    private final LoadBalancer loadBalancer;
    private final DataSyncService dataSyncService;
    private final ClusterCoordinator clusterCoordinator;
    private final FaultConfig config;

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("nodeId", config.getNodeId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/fault/status")
    public ResponseEntity<Map<String, Object>> getFaultStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("nodeId", config.getNodeId());
        status.put("nodeStatus", healthChecker.getAllNodeStatus());
        status.put("healthyNodes", healthChecker.getHealthyNodes());
        status.put("unhealthyNodes", healthChecker.getUnhealthyNodes());
        status.put("zookeeperConnected", clusterCoordinator.isConnected());
        status.put("zookeeperActiveNodes", clusterCoordinator.getActiveNodes());
        return ResponseEntity.ok(status);
    }

    @PostMapping("/fault/payment")
    public ResponseEntity<PaymentTransaction> routePayment(@RequestBody PaymentTransaction transaction) {
        PaymentTransaction result = loadBalancer.routePayment(transaction);
        if (result != null) {
            dataSyncService.storeTransaction(result);
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @PostMapping("/recovery/sync")
    public ResponseEntity<Map<String, Object>> syncRecovery() {
        int recoveredCount = dataSyncService.syncFromCluster();
        Map<String, Object> response = new HashMap<>();
        response.put("status", "completed");
        response.put("recoveredTransactions", recoveredCount);
        return ResponseEntity.ok(response);
    }
}

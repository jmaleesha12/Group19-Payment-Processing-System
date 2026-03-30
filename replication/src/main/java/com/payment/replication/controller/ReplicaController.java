package com.payment.replication.controller;

import com.payment.common.model.PaymentTransaction;
import com.payment.common.model.PaymentStatus;
import com.payment.replication.config.ReplicaConfig;
import com.payment.replication.service.DuplicateChecker;
import com.payment.replication.service.QuorumManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * REST endpoints for data replication operations.
 */
@RestController
@RequiredArgsConstructor
public class ReplicaController {

    private final QuorumManager quorumManager;
    private final DuplicateChecker duplicateChecker;
    private final ReplicaConfig config;

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "nodeId", config.getNodeId()));
    }

    @PostMapping("/payment")
    public ResponseEntity<PaymentTransaction> processPayment(@RequestBody PaymentTransaction transaction) {
        if (transaction.getTransactionId() == null) {
            transaction.setTransactionId(UUID.randomUUID().toString());
        }
        if (transaction.getCreatedTimestamp() == 0) {
            transaction.setCreatedTimestamp(System.currentTimeMillis());
        }

        PaymentTransaction result = quorumManager.processPayment(transaction);
        if (result.getStatus() == PaymentStatus.SUCCESS) return ResponseEntity.ok(result);
        if (result.getStatus() == PaymentStatus.DUPLICATE) return ResponseEntity.status(409).body(result);
        return ResponseEntity.status(503).body(result);
    }

    @PostMapping("/replicate")
    public ResponseEntity<PaymentTransaction> acceptReplicate(@RequestBody PaymentTransaction transaction) {
        return ResponseEntity.ok(quorumManager.acceptReplication(transaction));
    }

    @GetMapping("/transactions")
    public ResponseEntity<List<PaymentTransaction>> getAllTransactions() {
        return ResponseEntity.ok(quorumManager.getAllTransactions());
    }

    @GetMapping("/replication/status")
    public ResponseEntity<Map<String, Object>> getReplicationStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("nodeId", config.getNodeId());
        status.put("writeQuorum", config.getWriteQuorum());
        status.put("readQuorum", config.getReadQuorum());
        status.put("transactionCount", quorumManager.getAllTransactions().size());
        return ResponseEntity.ok(status);
    }
}

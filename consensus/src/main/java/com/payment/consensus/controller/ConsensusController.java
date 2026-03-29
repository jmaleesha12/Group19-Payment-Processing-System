package com.payment.consensus.controller;

import com.payment.common.model.PaymentTransaction;
import com.payment.common.model.PaymentStatus;
import com.payment.consensus.config.RaftConfig;
import com.payment.consensus.model.*;
import com.payment.consensus.service.ConsensusLog;
import com.payment.consensus.service.ConsensusNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * REST endpoints for Raft consensus operations.
 */
@RestController
@RequiredArgsConstructor
public class ConsensusController {

    private final ConsensusNode consensusNode;
    private final ConsensusLog consensusLog;
    private final RaftConfig config;

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "nodeId", config.getNodeId(), "role", consensusNode.getState().toString()));
    }

    @PostMapping("/raft/vote")
    public ResponseEntity<ElectionResponse> handleVote(@RequestBody ElectionRequest request) {
        return ResponseEntity.ok(consensusNode.handleVoteRequest(request));
    }

    @PostMapping("/raft/append")
    public ResponseEntity<LogReplicationResponse> handleAppendEntries(@RequestBody LogReplicationRequest request) {
        return ResponseEntity.ok(consensusNode.handleLogReplication(request));
    }

    @PostMapping("/raft/submit")
    public ResponseEntity<Map<String, Object>> submitPayment(@RequestBody PaymentTransaction transaction) {
        Map<String, Object> response = new HashMap<>();
        if (transaction.getTransactionId() == null) transaction.setTransactionId(UUID.randomUUID().toString());

        if (!consensusNode.isLeader()) {
            response.put("success", false);
            response.put("error", "Not the leader");
            response.put("leaderId", consensusNode.getLeaderId());
            return ResponseEntity.status(503).body(response);
        }

        PaymentTransaction result = consensusNode.submitTransaction(transaction);
        response.put("success", result.getStatus() == PaymentStatus.SUCCESS);
        response.put("transaction", result);
        return result.getStatus() == PaymentStatus.SUCCESS ? ResponseEntity.ok(response) : ResponseEntity.status(500).body(response);
    }

    @GetMapping("/raft/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(consensusNode.getStatus());
    }

    @GetMapping("/raft/transactions")
    public ResponseEntity<List<PaymentTransaction>> getTransactions() {
        return ResponseEntity.ok(consensusLog.getAppliedTransactions());
    }
}

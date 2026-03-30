package com.payment.replication.service;

import com.payment.common.model.PaymentTransaction;
import com.payment.common.model.PaymentStatus;
import com.payment.replication.config.ReplicaConfig;
import com.payment.replication.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages quorum-based replication (N=3, W=2, R=2).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuorumManager {

    private final ReplicaConfig config;
    private final PaymentRepository paymentRepository;
    private final DuplicateChecker duplicateChecker;
    private final RestTemplate restTemplate;

    public PaymentTransaction processPayment(PaymentTransaction transaction) {
        if (duplicateChecker.isDuplicate(transaction.getTransactionId())) {
            transaction.setStatus(PaymentStatus.DUPLICATE);
            return transaction;
        }

        transaction.setStatus(PaymentStatus.PROCESSING);
        transaction.setProcessedByNode(config.getNodeId());
        paymentRepository.save(transaction);

        int acknowledgments = replicateToQuorum(transaction);
        int totalAcks = acknowledgments + 1;

        if (totalAcks >= config.getWriteQuorum()) {
            transaction.setStatus(PaymentStatus.SUCCESS);
            log.info("Transaction {} committed with {} acks", transaction.getTransactionId(), totalAcks);
        } else {
            transaction.setStatus(PaymentStatus.FAILED);
            duplicateChecker.remove(transaction.getTransactionId());
        }

        paymentRepository.update(transaction);
        return transaction;
    }

    private int replicateToQuorum(PaymentTransaction transaction) {
        List<String> replicas = config.getReplicas();
        if (replicas.isEmpty()) return 0;

        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (String replicaUrl : replicas) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    ResponseEntity<PaymentTransaction> response = restTemplate.postForEntity(
                            replicaUrl + "/replicate", transaction, PaymentTransaction.class);
                    return response.getStatusCode().is2xxSuccessful();
                } catch (Exception e) {
                    return false;
                }
            }));
        }

        AtomicInteger successCount = new AtomicInteger(0);
        for (CompletableFuture<Boolean> future : futures) {
            try {
                if (Boolean.TRUE.equals(future.get(config.getReplicationTimeout(), TimeUnit.MILLISECONDS))) {
                    successCount.incrementAndGet();
                }
            } catch (Exception e) { }
        }
        return successCount.get();
    }

    public PaymentTransaction acceptReplication(PaymentTransaction transaction) {
        if (duplicateChecker.exists(transaction.getTransactionId())) {
            return paymentRepository.findById(transaction.getTransactionId()).orElse(transaction);
        }
        duplicateChecker.isDuplicate(transaction.getTransactionId());
        return paymentRepository.save(transaction);
    }

    public List<PaymentTransaction> getAllTransactions() {
        return paymentRepository.findAll();
    }

    public PaymentTransaction getTransaction(String transactionId) {
        return paymentRepository.findById(transactionId).orElse(null);
    }
}

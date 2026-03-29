package com.payment.fault.service;

import com.payment.common.model.PaymentTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles data synchronization after node recovery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSyncService {

    private final RestTemplate restTemplate;
    private final NodeHealthChecker healthChecker;
    private final ConcurrentHashMap<String, PaymentTransaction> localTransactions = new ConcurrentHashMap<>();

    public int syncFromCluster() {
        int recoveredCount = 0;
        for (String healthyNode : healthChecker.getHealthyNodes()) {
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> transactions = restTemplate.getForObject(
                        healthyNode + "/transactions", List.class);
                if (transactions != null) {
                    for (Map<String, Object> txData : transactions) {
                        PaymentTransaction tx = mapToTransaction(txData);
                        if (tx != null && localTransactions.putIfAbsent(tx.getTransactionId(), tx) == null) {
                            recoveredCount++;
                        }
                    }
                }
                break;
            } catch (Exception e) {
                log.warn("Failed to sync from {}", healthyNode);
            }
        }
        return recoveredCount;
    }

    public Map<String, PaymentTransaction> getLocalTransactions() {
        return new ConcurrentHashMap<>(localTransactions);
    }

    public void storeTransaction(PaymentTransaction transaction) {
        localTransactions.put(transaction.getTransactionId(), transaction);
    }

    private PaymentTransaction mapToTransaction(Map<String, Object> data) {
        try {
            return PaymentTransaction.builder()
                    .transactionId((String) data.get("transactionId"))
                    .amount(((Number) data.get("amount")).doubleValue())
                    .currency((String) data.get("currency"))
                    .fromAccount((String) data.get("fromAccount"))
                    .toAccount((String) data.get("toAccount"))
                    .createdTimestamp(((Number) data.get("createdTimestamp")).longValue())
                    .processedByNode((String) data.get("processedByNode"))
                    .build();
        } catch (Exception e) {
            return null;
        }
    }
}

package com.payment.replication.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects and rejects duplicate transactions using thread-safe operations.
 */
@Slf4j
@Service
public class DuplicateChecker {

    private final ConcurrentHashMap<String, Boolean> processedTransactions = new ConcurrentHashMap<>();

    public boolean isDuplicate(String transactionId) {
        Boolean existing = processedTransactions.putIfAbsent(transactionId, Boolean.TRUE);
        if (existing != null) {
            log.warn("Duplicate transaction detected: {}", transactionId);
            return true;
        }
        return false;
    }

    public boolean exists(String transactionId) {
        return processedTransactions.containsKey(transactionId);
    }

    public int getProcessedCount() {
        return processedTransactions.size();
    }

    public boolean remove(String transactionId) {
        return processedTransactions.remove(transactionId) != null;
    }
}

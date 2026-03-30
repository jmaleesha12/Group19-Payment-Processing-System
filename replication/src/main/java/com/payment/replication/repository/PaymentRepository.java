package com.payment.replication.repository;

import com.payment.common.model.PaymentTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory storage for payment transactions.
 */
@Slf4j
@Repository
public class PaymentRepository {

    private final ConcurrentHashMap<String, PaymentTransaction> transactions = new ConcurrentHashMap<>();

    public PaymentTransaction save(PaymentTransaction transaction) {
        transactions.put(transaction.getTransactionId(), transaction);
        return transaction;
    }

    public Optional<PaymentTransaction> findById(String transactionId) {
        return Optional.ofNullable(transactions.get(transactionId));
    }

    public List<PaymentTransaction> findAll() {
        return new ArrayList<>(transactions.values());
    }

    public PaymentTransaction update(PaymentTransaction transaction) {
        transactions.put(transaction.getTransactionId(), transaction);
        return transaction;
    }
}

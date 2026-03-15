package com.payment.model;

public enum PaymentStatus {
    PENDING, // Just created, not yet processed
    PROCESSING, // Currently being replicated across nodes
    SUCCESS, // Replicated to quorum and committed
    FAILED, // Replication failed or rejected
    DUPLICATE // Already processed (deduplication caught it)
}


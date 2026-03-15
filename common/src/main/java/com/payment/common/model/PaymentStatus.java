package com.payment.common.model;

/**
 * Enum representing the status of a payment transaction.
 */
public enum PaymentStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    DUPLICATE
}

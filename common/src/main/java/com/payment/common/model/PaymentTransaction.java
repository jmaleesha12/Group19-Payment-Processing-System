package com.payment.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a payment transaction in the distributed payment system.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentTransaction {

    private String transactionId;
    private double amount;
    private String currency;
    private String fromAccount;
    private String toAccount;
    private PaymentStatus status;
    private long createdTimestamp;
    private String processedByNode;
}

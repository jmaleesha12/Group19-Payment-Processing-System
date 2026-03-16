package com.payment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payment entity for in-memory storage.
 * Represents a payment transaction processed through Stripe.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    private Long id;
    private String stripePaymentIntentId;
    private BigDecimal amount;
    private String currency;
    private String description;
    private PaymentStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String nodeId;
}

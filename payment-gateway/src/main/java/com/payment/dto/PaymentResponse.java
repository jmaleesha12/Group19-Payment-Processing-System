package com.payment.dto;

import com.payment.model.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for payment operations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {

    private String id;
    private String stripePaymentIntentId;
    private Long amount;
    private String currency;
    private String description;
    private PaymentStatus status;
    private String clientSecret; // For frontend confirmation
    private String errorMessage;
    private LocalDateTime createdAt;
    private String nodeId; // Which node processed this payment
}

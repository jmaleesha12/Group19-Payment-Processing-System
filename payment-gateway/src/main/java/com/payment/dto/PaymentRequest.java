package com.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a payment.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequest {

    @NotNull(message = "Amount is required")
    @Min(value = 1, message = "Amount must be at least 1 cent")
    private Long amount; // Amount in cents (e.g., 1000 = $10.00)

    @NotBlank(message = "Currency is required")
    private String currency; // ISO currency code (e.g., "usd", "eur")

    private String description;

    // For card payments
    private String paymentMethodId; // Stripe PaymentMethod ID (pm_xxx)

    // For simple test payments with card details
    private String cardNumber;
    private String expMonth;
    private String expYear;
    private String cvc;

    // Customer information
    private String customerEmail;
    private String customerName;
}

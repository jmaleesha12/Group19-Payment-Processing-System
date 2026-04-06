package com.payment.service;

import com.payment.dto.PaymentRequest;
import com.payment.dto.PaymentResponse;
import com.payment.model.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PaymentService with Stripe.
 * Uses Stripe test mode.
 */
@SpringBootTest
class PaymentServiceTest {

    @Autowired
    private PaymentService paymentService;

    @Test
    @DisplayName("Should create PaymentIntent successfully")
    void shouldCreatePaymentIntent() {
        PaymentRequest request = PaymentRequest.builder()
                .amount(1000L) // $10.00
                .currency("usd")
                .description("Test payment")
                .build();

        PaymentResponse response = paymentService.createPaymentIntent(request);

        assertNotNull(response);
        assertNotNull(response.getId());
        assertNotNull(response.getStripePaymentIntentId());
        assertNotNull(response.getClientSecret());
        assertEquals(PaymentStatus.PENDING, response.getStatus());
    }

    @Test
    @DisplayName("Should process payment with test card")
    void shouldProcessPaymentWithTestCard() {
        PaymentRequest request = PaymentRequest.builder()
                .amount(2000L) // $20.00
                .currency("usd")
                .description("Test card payment")
                .cardNumber("4242424242424242") // Stripe test card
                .expMonth("12")
                .expYear("2026")
                .cvc("123")
                .build();

        PaymentResponse response = paymentService.processPayment(request);

        assertNotNull(response);
        assertNotNull(response.getId());
        // Payment should succeed with test card
        assertEquals(PaymentStatus.SUCCESS, response.getStatus());
    }

    @Test
    @DisplayName("Should retrieve payment by ID")
    void shouldRetrievePaymentById() {
        // First create a payment
        PaymentRequest request = PaymentRequest.builder()
                .amount(500L)
                .currency("usd")
                .description("Retrievable payment")
                .build();

        PaymentResponse created = paymentService.createPaymentIntent(request);

        // Then retrieve it
        PaymentResponse retrieved = paymentService.getPayment(created.getId());

        assertNotNull(retrieved);
        assertEquals(created.getId(), retrieved.getId());
    }
}

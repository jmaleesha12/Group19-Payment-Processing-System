package com.payment.controller;

import com.payment.dto.PaymentRequest;
import com.payment.dto.PaymentResponse;
import com.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for payment operations.
 * Integrates with Stripe for payment processing.
 */
@Slf4j
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // For testing - restrict in production
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * Create a PaymentIntent for client-side confirmation.
     * Use this when you want to collect card details on the frontend with Stripe.js
     *
     * POST /api/payments/intent
     * Body: { "amount": 1000, "currency": "usd", "description": "Test payment" }
     */
    @PostMapping("/intent")
    public ResponseEntity<PaymentResponse> createPaymentIntent(@Valid @RequestBody PaymentRequest request) {
        log.info("Received createPaymentIntent request: {} {}", request.getAmount(), request.getCurrency());
        PaymentResponse response = paymentService.createPaymentIntent(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Process a payment directly (for testing with card details).
     * Creates and confirms the payment in one step.
     *
     * POST /api/payments/process
     * Body: {
     *   "amount": 1000,
     *   "currency": "usd",
     *   "cardNumber": "4242424242424242",
     *   "expMonth": "12",
     *   "expYear": "2025",
     *   "cvc": "123"
     * }
     */
    @PostMapping("/process")
    public ResponseEntity<PaymentResponse> processPayment(@Valid @RequestBody PaymentRequest request) {
        log.info("Received processPayment request: {} {}", request.getAmount(), request.getCurrency());
        PaymentResponse response = paymentService.processPayment(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Quick test payment using Stripe test payment method tokens.
     * No request body needed - uses default test values.
     *
     * POST /api/payments/test?amount=1000&currency=usd&card=visa
     *
     * Available test cards: visa, mastercard, amex, declined
     */
    @PostMapping("/test")
    public ResponseEntity<PaymentResponse> testPayment(
            @RequestParam(defaultValue = "1000") Long amount,
            @RequestParam(defaultValue = "usd") String currency,
            @RequestParam(defaultValue = "Test Payment") String description,
            @RequestParam(defaultValue = "visa") String card) {

        log.info("Received test payment request: {} {} with card type: {}", amount, currency, card);

        // Map card type to Stripe test payment method token
        String paymentMethodToken = switch (card.toLowerCase()) {
            case "mastercard" -> "pm_card_mastercard";
            case "amex" -> "pm_card_amex";
            case "declined" -> "pm_card_visa_chargeDeclined";
            default -> "pm_card_visa"; // Default to Visa
        };

        PaymentRequest request = PaymentRequest.builder()
                .amount(amount)
                .currency(currency)
                .description(description)
                .paymentMethodId(paymentMethodToken)
                .build();

        PaymentResponse response = paymentService.processPayment(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Confirm a PaymentIntent with a PaymentMethod.
     *
     * POST /api/payments/confirm
     * Body: { "paymentIntentId": "pi_xxx", "paymentMethodId": "pm_xxx" }
     */
    @PostMapping("/confirm")
    public ResponseEntity<PaymentResponse> confirmPayment(@RequestBody Map<String, String> request) {
        String paymentIntentId = request.get("paymentIntentId");
        String paymentMethodId = request.get("paymentMethodId");

        log.info("Confirming PaymentIntent: {} with PaymentMethod: {}", paymentIntentId, paymentMethodId);
        PaymentResponse response = paymentService.confirmPayment(paymentIntentId, paymentMethodId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get payment by ID.
     *
     * GET /api/payments/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable String id) {
        log.info("Getting payment: {}", id);
        PaymentResponse response = paymentService.getPayment(id);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Get all payments on this node.
     *
     * GET /api/payments
     */
    @GetMapping
    public ResponseEntity<List<PaymentResponse>> getAllPayments() {
        log.info("Getting all payments");
        List<PaymentResponse> payments = paymentService.getAllPayments();
        return ResponseEntity.ok(payments);
    }

    /**
     * Health check endpoint for payments.
     *
     * GET /api/payments/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "payment-gateway",
                "timestamp", System.currentTimeMillis()
        ));
    }
}

package com.payment.service;

import com.payment.dto.PaymentRequest;
import com.payment.dto.PaymentResponse;
import com.payment.model.Payment;
import com.payment.model.PaymentStatus;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.param.PaymentIntentConfirmParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentMethodCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Payment service with Stripe integration.
 * Handles payment creation, processing, and tracking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    @Value("${app.node.id:node1}")
    private String nodeId;

    // In-memory storage for demo (use database in production)
    private final ConcurrentHashMap<String, Payment> paymentStore = new ConcurrentHashMap<>();

    /**
     * Create a PaymentIntent for client-side confirmation.
     * Returns a client_secret that can be used with Stripe.js
     */
    public PaymentResponse createPaymentIntent(PaymentRequest request) {
        String paymentId = UUID.randomUUID().toString();
        log.info("Creating PaymentIntent: {} for amount {} {}", paymentId, request.getAmount(), request.getCurrency());

        try {
            PaymentIntentCreateParams.Builder paramsBuilder = PaymentIntentCreateParams.builder()
                    .setAmount(request.getAmount())
                    .setCurrency(request.getCurrency().toLowerCase())
                    .putMetadata("internal_payment_id", paymentId)
                    .putMetadata("node_id", nodeId);

            if (request.getDescription() != null) {
                paramsBuilder.setDescription(request.getDescription());
            }

            // For automatic confirmation (simple flow)
            if (request.getPaymentMethodId() != null) {
                paramsBuilder.setPaymentMethod(request.getPaymentMethodId());
            }

            PaymentIntent paymentIntent = PaymentIntent.create(paramsBuilder.build());

            // Store locally
            Payment payment = Payment.builder()
                    .id(Long.parseLong(paymentId.substring(0, 8), 16) & 0x7FFFFFFFL)
                    .amount(BigDecimal.valueOf(request.getAmount()).divide(BigDecimal.valueOf(100)))
                    .currency(request.getCurrency().toUpperCase())
                    .description(request.getDescription())
                    .status(PaymentStatus.PENDING)
                    .createdAt(LocalDateTime.now())
                    .build();
            paymentStore.put(paymentId, payment);

            log.info("PaymentIntent created: {}", paymentIntent.getId());

            return PaymentResponse.builder()
                    .id(paymentId)
                    .stripePaymentIntentId(paymentIntent.getId())
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .description(request.getDescription())
                    .status(PaymentStatus.PENDING)
                    .clientSecret(paymentIntent.getClientSecret())
                    .createdAt(LocalDateTime.now())
                    .nodeId(nodeId)
                    .build();

        } catch (StripeException e) {
            log.error("Stripe error creating PaymentIntent: {}", e.getMessage());
            return PaymentResponse.builder()
                    .id(paymentId)
                    .status(PaymentStatus.FAILED)
                    .errorMessage(e.getMessage())
                    .createdAt(LocalDateTime.now())
                    .nodeId(nodeId)
                    .build();
        }
    }

    /**
     * Process a payment with a payment method token (for testing).
     * Uses Stripe test tokens like "pm_card_visa" for test mode.
     */
    public PaymentResponse processPayment(PaymentRequest request) {
        String paymentId = UUID.randomUUID().toString();
        log.info("Processing payment: {} for amount {} {}", paymentId, request.getAmount(), request.getCurrency());

        try {
            // Use payment method token (pm_card_visa, pm_card_mastercard, etc.)
            String paymentMethodToken = request.getPaymentMethodId();
            if (paymentMethodToken == null) {
                // Default to Visa test card token
                paymentMethodToken = "pm_card_visa";
            }

            // Create PaymentIntent with explicit payment method (not automatic)
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(request.getAmount())
                    .setCurrency(request.getCurrency().toLowerCase())
                    .setPaymentMethod(paymentMethodToken)
                    .setConfirm(true) // Auto-confirm
                    .setReturnUrl("https://example.com/return") // Required for some payment methods
                    .putMetadata("internal_payment_id", paymentId)
                    .putMetadata("node_id", nodeId)
                    .setDescription(request.getDescription())
                    .build();

            PaymentIntent paymentIntent = PaymentIntent.create(params);

            // Determine status from Stripe response
            PaymentStatus status = mapStripeStatus(paymentIntent.getStatus());

            // Store locally
            Payment payment = Payment.builder()
                    .id(Long.parseLong(paymentId.substring(0, 8), 16) & 0x7FFFFFFFL)
                    .amount(BigDecimal.valueOf(request.getAmount()).divide(BigDecimal.valueOf(100)))
                    .currency(request.getCurrency().toUpperCase())
                    .description(request.getDescription())
                    .status(status)
                    .createdAt(LocalDateTime.now())
                    .build();
            paymentStore.put(paymentId, payment);

            log.info("Payment processed: {} with status: {}", paymentIntent.getId(), status);

            return PaymentResponse.builder()
                    .id(paymentId)
                    .stripePaymentIntentId(paymentIntent.getId())
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .description(request.getDescription())
                    .status(status)
                    .clientSecret(paymentIntent.getClientSecret())
                    .createdAt(LocalDateTime.now())
                    .nodeId(nodeId)
                    .build();

        } catch (StripeException e) {
            log.error("Stripe error processing payment: {}", e.getMessage());

            // Store failed payment
            Payment payment = Payment.builder()
                    .amount(BigDecimal.valueOf(request.getAmount()).divide(BigDecimal.valueOf(100)))
                    .currency(request.getCurrency().toUpperCase())
                    .description(request.getDescription())
                    .status(PaymentStatus.FAILED)
                    .createdAt(LocalDateTime.now())
                    .build();
            paymentStore.put(paymentId, payment);

            return PaymentResponse.builder()
                    .id(paymentId)
                    .amount(request.getAmount())
                    .currency(request.getCurrency())
                    .status(PaymentStatus.FAILED)
                    .errorMessage(e.getMessage())
                    .createdAt(LocalDateTime.now())
                    .nodeId(nodeId)
                    .build();
        }
    }

    /**
     * Get payment status by ID
     */
    public PaymentResponse getPayment(String paymentId) {
        Payment payment = paymentStore.get(paymentId);
        if (payment == null) {
            return null;
        }
        return PaymentResponse.builder()
                .id(paymentId)
                .amount(payment.getAmount().multiply(BigDecimal.valueOf(100)).longValue())
                .currency(payment.getCurrency())
                .description(payment.getDescription())
                .status(payment.getStatus())
                .createdAt(payment.getCreatedAt())
                .nodeId(nodeId)
                .build();
    }

    /**
     * Get all payments stored on this node
     */
    public List<PaymentResponse> getAllPayments() {
        return paymentStore.entrySet().stream()
                .map(entry -> {
                    Payment p = entry.getValue();
                    return PaymentResponse.builder()
                            .id(entry.getKey())
                            .amount(p.getAmount().multiply(BigDecimal.valueOf(100)).longValue())
                            .currency(p.getCurrency())
                            .description(p.getDescription())
                            .status(p.getStatus())
                            .createdAt(p.getCreatedAt())
                            .nodeId(nodeId)
                            .build();
                })
                .toList();
    }

    /**
     * Confirm a PaymentIntent (after client-side card details)
     */
    public PaymentResponse confirmPayment(String paymentIntentId, String paymentMethodId) {
        try {
            PaymentIntent paymentIntent = PaymentIntent.retrieve(paymentIntentId);

            PaymentIntentConfirmParams confirmParams = PaymentIntentConfirmParams.builder()
                    .setPaymentMethod(paymentMethodId)
                    .build();

            paymentIntent = paymentIntent.confirm(confirmParams);

            PaymentStatus status = mapStripeStatus(paymentIntent.getStatus());
            String internalId = paymentIntent.getMetadata().get("internal_payment_id");

            // Update local storage
            if (internalId != null && paymentStore.containsKey(internalId)) {
                Payment payment = paymentStore.get(internalId);
                payment.setStatus(status);
                payment.setUpdatedAt(LocalDateTime.now());
            }

            log.info("Payment confirmed: {} with status: {}", paymentIntentId, status);

            return PaymentResponse.builder()
                    .id(internalId)
                    .stripePaymentIntentId(paymentIntentId)
                    .status(status)
                    .nodeId(nodeId)
                    .build();

        } catch (StripeException e) {
            log.error("Error confirming payment: {}", e.getMessage());
            return PaymentResponse.builder()
                    .stripePaymentIntentId(paymentIntentId)
                    .status(PaymentStatus.FAILED)
                    .errorMessage(e.getMessage())
                    .nodeId(nodeId)
                    .build();
        }
    }

    /**
     * Create a PaymentMethod from card details (for testing)
     */
    private PaymentMethod createPaymentMethod(PaymentRequest request) throws StripeException {
        PaymentMethodCreateParams params = PaymentMethodCreateParams.builder()
                .setType(PaymentMethodCreateParams.Type.CARD)
                .setCard(PaymentMethodCreateParams.CardDetails.builder()
                        .setNumber(request.getCardNumber())
                        .setExpMonth(Long.parseLong(request.getExpMonth()))
                        .setExpYear(Long.parseLong(request.getExpYear()))
                        .setCvc(request.getCvc())
                        .build())
                .build();

        return PaymentMethod.create(params);
    }

    /**
     * Map Stripe status to internal PaymentStatus
     */
    private PaymentStatus mapStripeStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "succeeded" -> PaymentStatus.SUCCESS;
            case "processing" -> PaymentStatus.PROCESSING;
            case "requires_payment_method", "requires_confirmation", "requires_action" -> PaymentStatus.PENDING;
            case "canceled" -> PaymentStatus.FAILED;
            default -> PaymentStatus.PENDING;
        };
    }
}

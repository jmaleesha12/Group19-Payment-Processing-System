package com.payment.config;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Stripe payment gateway configuration.
 * Uses Stripe Test Mode for development/testing.
 */
@Slf4j
@Configuration
@Getter
public class StripeConfig {

    @Value("${stripe.api.key}")
    private String apiKey;

    @Value("${stripe.webhook.secret:}")
    private String webhookSecret;

    @PostConstruct
    public void init() {
        Stripe.apiKey = apiKey;
        log.info("Stripe SDK initialized with API key: {}...",
                apiKey.substring(0, Math.min(12, apiKey.length())));
    }
}

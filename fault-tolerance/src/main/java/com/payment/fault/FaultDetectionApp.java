package com.payment.fault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Fault Detection service.
 */
@SpringBootApplication
@EnableScheduling
public class FaultDetectionApp {

    public static void main(String[] args) {
        SpringApplication.run(FaultDetectionApp.class, args);
    }
}

package com.payment.consensus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Leader Election service.
 */
@SpringBootApplication
@EnableScheduling
public class LeaderElectionApp {

    public static void main(String[] args) {
        SpringApplication.run(LeaderElectionApp.class, args);
    }
}

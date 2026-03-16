package com.payment.timesync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Clock Synchronization service.
 */
@SpringBootApplication
@EnableScheduling
public class ClockSyncApp {

    public static void main(String[] args) {
        SpringApplication.run(ClockSyncApp.class, args);
    }
}

package com.payment.replication;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Data Replication service.
 */
@SpringBootApplication
public class DataReplicationApp {

    public static void main(String[] args) {
        SpringApplication.run(DataReplicationApp.class, args);
    }
}

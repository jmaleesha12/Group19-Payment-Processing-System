package com.payment.timesync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for clock synchronization.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "time-sync")
public class ClockConfig {

}

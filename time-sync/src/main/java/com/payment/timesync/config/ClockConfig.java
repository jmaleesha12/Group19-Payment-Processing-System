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

    private String nodeId = "node1";
    
    private String ntpServer = "pool.ntp.org";
    private int ntpPort = 123;
    private long syncIntervalMs = 60000 ;
    private int ntpTimeout = 5000;

}

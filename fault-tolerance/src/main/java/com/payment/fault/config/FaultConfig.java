package com.payment.fault.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for fault detection.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "fault-tolerance")
public class FaultConfig {

    private String nodeId = "node1";
    private List<String> peers = new ArrayList<>();
    private String zookeeperHost = "localhost:2181";
    private int zookeeperSessionTimeout = 5000;
    private int maxMissedHeartbeats = 3;
}

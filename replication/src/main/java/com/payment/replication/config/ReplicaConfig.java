package com.payment.replication.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for quorum-based replication.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "replication")
public class ReplicaConfig {

    private String nodeId = "node1";
    private int totalNodes = 3;
    private int writeQuorum = 2;
    private int readQuorum = 2;
    private List<String> replicas = new ArrayList<>();
    private int replicationTimeout = 5000;
}

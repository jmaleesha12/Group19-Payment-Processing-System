package com.payment.consensus.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for Raft consensus algorithm.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "consensus")
public class RaftConfig {

    private String nodeId = "node1";
    private List<String> peers = new ArrayList<>();
    private int electionTimeoutMin = 1500;
    private int electionTimeoutMax = 3000;
    private int heartbeatInterval = 500;
    private int rpcTimeout = 1000;
}

package com.payment.fault.service;

import com.payment.fault.config.FaultConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Monitors node health through periodic heartbeat checks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NodeHealthChecker {

    private final FaultConfig config;
    private final RestTemplate restTemplate;
    private final ConcurrentHashMap<String, Integer> missedHeartbeats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> nodeStatus = new ConcurrentHashMap<>();

    @Scheduled(fixedRate = 2000)
    public void checkPeerHealth() {
        for (String peer : config.getPeers()) {
            try {
                String healthUrl = peer + "/health";
                restTemplate.getForObject(healthUrl, Map.class);
                missedHeartbeats.put(peer, 0);
                boolean wasDown = Boolean.FALSE.equals(nodeStatus.put(peer, true));
                if (wasDown) {
                    log.info("Node {} is now UP", peer);
                }
            } catch (Exception e) {
                int failures = missedHeartbeats.merge(peer, 1, Integer::sum);
                log.debug("Health check failed for {}: {} consecutive failures", peer, failures);
                if (failures >= config.getMaxMissedHeartbeats()) {
                    boolean wasUp = !Boolean.FALSE.equals(nodeStatus.put(peer, false));
                    if (wasUp) {
                        log.warn("Node {} is now DOWN after {} missed heartbeats", peer, failures);
                    }
                }
            }
        }
    }

    public Map<String, Boolean> getAllNodeStatus() {
        return new ConcurrentHashMap<>(nodeStatus);
    }

    public Set<String> getHealthyNodes() {
        return nodeStatus.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    public Set<String> getUnhealthyNodes() {
        return nodeStatus.entrySet().stream()
                .filter(entry -> !entry.getValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    public boolean isNodeHealthy(String nodeUrl) {
        return Boolean.TRUE.equals(nodeStatus.get(nodeUrl));
    }
}

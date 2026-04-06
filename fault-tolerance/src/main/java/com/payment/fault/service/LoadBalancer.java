package com.payment.fault.service;

import com.payment.common.model.PaymentTransaction;
import com.payment.fault.config.FaultConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Routes requests to healthy nodes using round-robin load balancing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoadBalancer {

    private final NodeHealthChecker healthChecker;
    private final RestTemplate restTemplate;
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    public PaymentTransaction routePayment(PaymentTransaction transaction) {
        Set<String> healthyNodes = healthChecker.getHealthyNodes();
        if (healthyNodes.isEmpty()) {
            log.error("No healthy nodes available");
            return null;
        }

        List<String> nodeList = new ArrayList<>(healthyNodes);
        int index = Math.abs(roundRobinIndex.getAndIncrement() % nodeList.size());
        String targetNode = nodeList.get(index);

        log.info("Routing payment {} to node {}", transaction.getTransactionId(), targetNode);

        try {
            ResponseEntity<PaymentTransaction> response = restTemplate.postForEntity(
                    targetNode + "/payment", transaction, PaymentTransaction.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            }
        } catch (Exception e) {
            log.error("Error routing to {}: {}", targetNode, e.getMessage());
        }
        return retryWithNextNode(transaction, nodeList, index);
    }

    private PaymentTransaction retryWithNextNode(PaymentTransaction tx, List<String> nodeList, int failedIndex) {
        for (int i = 1; i < nodeList.size(); i++) {
            int nextIndex = (failedIndex + i) % nodeList.size();
            String nextNode = nodeList.get(nextIndex);
            try {
                ResponseEntity<PaymentTransaction> response = restTemplate.postForEntity(
                        nextNode + "/payment", tx, PaymentTransaction.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    return response.getBody();
                }
            } catch (Exception e) {
                log.error("Retry to {} failed", nextNode);
            }
        }
        return null;
    }

    public int getHealthyNodeCount() {
        return healthChecker.getHealthyNodes().size();
    }
}

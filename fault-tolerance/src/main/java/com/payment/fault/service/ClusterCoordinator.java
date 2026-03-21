package com.payment.fault.service;

import com.payment.fault.config.FaultConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.zookeeper.*;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates cluster membership using ZooKeeper.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterCoordinator implements Watcher {

    private static final String NODES_PATH = "/payment-cluster/nodes";
    private final FaultConfig config;
    private ZooKeeper zooKeeper;
    private volatile boolean connected = false;

    @PostConstruct
    public void init() {
        try {
            connect();
            registerNode();
        } catch (Exception e) {
            log.error("Failed to initialize ZooKeeper connection", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (zooKeeper != null) zooKeeper.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void connect() throws IOException, InterruptedException {
        CountDownLatch connectionLatch = new CountDownLatch(1);
        zooKeeper = new ZooKeeper(config.getZookeeperHost(), config.getZookeeperSessionTimeout(),
                event -> {
                    if (event.getState() == Event.KeeperState.SyncConnected) {
                        connected = true;
                        connectionLatch.countDown();
                    }
                });
        connectionLatch.await(10, TimeUnit.SECONDS);
    }

    private void registerNode() {
        if (!connected || zooKeeper == null) return;
        try {
            createPathIfNotExists(NODES_PATH);
            String nodePath = NODES_PATH + "/" + config.getNodeId();
            if (zooKeeper.exists(nodePath, false) == null) {
                zooKeeper.create(nodePath, config.getNodeId().getBytes(),
                        ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
                log.info("Registered node {} in ZooKeeper", config.getNodeId());
            }
        } catch (Exception e) {
            log.error("Failed to register node", e);
        }
    }

    private void createPathIfNotExists(String path) throws KeeperException, InterruptedException {
        String[] parts = path.split("/");
        StringBuilder currentPath = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            currentPath.append("/").append(part);
            if (zooKeeper.exists(currentPath.toString(), false) == null) {
                try {
                    zooKeeper.create(currentPath.toString(), new byte[0],
                            ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                } catch (KeeperException.NodeExistsException e) { }
            }
        }
    }

    public List<String> getActiveNodes() {
        if (!connected || zooKeeper == null) return Collections.emptyList();
        try {
            return zooKeeper.getChildren(NODES_PATH, false);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public boolean isConnected() {
        return connected && zooKeeper != null && zooKeeper.getState().isConnected();
    }

    @Override
    public void process(WatchedEvent event) {
        if (event.getState() == Event.KeeperState.Disconnected) connected = false;
        else if (event.getState() == Event.KeeperState.SyncConnected) connected = true;
    }
}

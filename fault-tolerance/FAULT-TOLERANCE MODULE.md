# Fault Tolerance Module — Distributed Payment Processing System

The `fault-tolerance` module is the **reliability layer** of the distributed payment processing system. It continuously monitors the health of every node in the cluster, automatically reroutes payment traffic away from failed nodes, and synchronises transaction data when a crashed node recovers — all without any manual intervention.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [How It Works](#how-it-works)
  - [1. Cluster Membership via ZooKeeper](#1-cluster-membership-via-zookeeper)
  - [2. Node Health Checking](#2-node-health-checking)
  - [3. Load Balancing with Automatic Failover](#3-load-balancing-with-automatic-failover)
  - [4. Post-Recovery Data Sync](#4-post-recovery-data-sync)
  - [5. End-to-End Payment Routing Flow](#5-end-to-end-payment-routing-flow)
- [Configuration](#configuration)
- [API Reference](#api-reference)
- [Running the Module](#running-the-module)

---

## Overview

The fault-tolerance module runs alongside every node in the payment cluster. Each instance has four core responsibilities:

1. **Cluster registration** — Registers itself as an ephemeral ZooKeeper node so the rest of the cluster knows it is alive.
2. **Health polling** — Pings every peer's `/health` endpoint every 2 seconds and marks nodes as UP or DOWN after tracking consecutive failures.
3. **Smart routing** — When a payment arrives at `POST /fault/payment`, it is round-robin routed to a healthy peer. If the chosen peer fails mid-request, the load balancer immediately retries the remaining healthy nodes.
4. **Recovery sync** — When a previously downed node returns, a single call to `POST /recovery/sync` pulls all missing transactions from a healthy peer and rebuilds the local transaction store.

```
                     ┌─────────────────────────────────────────┐
                     │         Payment Client / Gateway         │
                     └───────────────┬─────────────────────────┘
                                     │  POST /fault/payment
                                     ▼
                     ┌─────────────────────────────────────────┐
                     │        fault-tolerance  (node1)          │  :8081
                     │                                         │
                     │  NodeHealthChecker ──► LoadBalancer      │
                     │  ClusterCoordinator (ZooKeeper)          │
                     │  DataSyncService                         │
                     └──────┬──────────────────────┬───────────┘
           GET /health       │                      │  POST /payment
           every 2s          │                      │  (routed payment)
                             ▼                      ▼
             ┌───────────────────┐     ┌───────────────────┐
             │  peer node2 :8082 │     │  peer node3 :8083 │
             └───────────────────┘     └───────────────────┘

                     ┌─────────────────────────────────────────┐
                     │          Apache ZooKeeper  :2181         │
                     │   /payment-cluster/nodes/node1 (ephemeral│
                     │   /payment-cluster/nodes/node2 (ephemeral│
                     │   /payment-cluster/nodes/node3 (ephemeral│
                     └─────────────────────────────────────────┘
```

---

## Architecture

### Microservice Architecture

The fault-tolerance module is one service within a larger **multi-module Maven project** (`distributed-payment-system`). It depends on the `common` module for shared domain models (`PaymentTransaction`, `PaymentStatus`).

```
distributed-payment-system/          ← Parent Maven project
├── common/                          ← Shared models (PaymentTransaction, PaymentStatus)
└── fault-tolerance/                 ← This module — fault detection & recovery service
    ├── config/                      ← FaultConfig, HttpClientConfig
    ├── controller/                  ← REST endpoints (health, routing, recovery)
    └── service/                     ← NodeHealthChecker, ClusterCoordinator,
                                         LoadBalancer, DataSyncService
```

### Internal Component Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                       Fault Tolerance Module                         │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                 ClusterHealthController (REST)                 │  │
│  │  GET /health   GET /fault/status   POST /fault/payment         │  │
│  │  POST /recovery/sync                                           │  │
│  └─────────┬────────────────────────────────────────────┬─────────┘  │
│            │                                            │             │
│   ┌────────▼───────────┐                   ┌───────────▼──────────┐  │
│   │  NodeHealthChecker │                   │     LoadBalancer      │  │
│   │  @Scheduled/2000ms │                   │   Round-robin with    │  │
│   │  Pings /health on  │◄──── healthy? ────│   automatic failover  │  │
│   │  every peer        │                   │  (retries on failure) │  │
│   │  Tracks missed     │                   └──────────────────────┘  │
│   │  heartbeats count  │                                              │
│   └────────────────────┘                                              │
│                                                                      │
│   ┌──────────────────────────────┐  ┌──────────────────────────────┐ │
│   │    ClusterCoordinator        │  │      DataSyncService          │ │
│   │   Connects to ZooKeeper      │  │  Pulls missing transactions   │ │
│   │   Creates ephemeral znode    │  │  from healthy peer on recovery│ │
│   │   /payment-cluster/nodes/X   │  │  Local ConcurrentHashMap      │ │
│   │   Watches SyncConnected /    │  │  as in-memory store           │ │
│   │   Disconnected events        │  └──────────────────────────────┘ │
│   └──────────────────────────────┘                                    │
│                                                                      │
│   ┌──────────────┐   ┌──────────────────────────────────────────┐   │
│   │  FaultConfig  │   │              HttpClientConfig             │   │
│   │  (node-id,    │   │   RestTemplate: 2s connect / 2s read     │   │
│   │   peers,      │   │   timeout — short enough to detect       │   │
│   │   ZK host,    │   │   failures quickly                       │   │
│   │   thresholds) │   └──────────────────────────────────────────┘   │
│   └──────────────┘                                                    │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17+ |
| Framework | Spring Boot |
| Build Tool | Apache Maven (multi-module) |
| HTTP Server | Spring Web (embedded Tomcat) |
| Cluster Coordination | Apache ZooKeeper (ephemeral znodes for membership) |
| Health Polling | Spring `@Scheduled` (every 2 000 ms) |
| Inter-node HTTP | Spring `RestTemplate` (2 s connect + read timeout) |
| Observability | Spring Boot Actuator |
| Boilerplate Reduction | Lombok (`@Data`, `@Slf4j`, `@RequiredArgsConstructor`) |
| Thread Safety | `ConcurrentHashMap`, `AtomicInteger`, `volatile`, `CountDownLatch` |

---

## Project Structure

```
fault-tolerance/
├── pom.xml
└── src/main/
    ├── java/com/payment/fault/
    │   ├── FaultDetectionApp.java                  ← Spring Boot entry point
    │   ├── config/
    │   │   ├── FaultConfig.java                    ← All tunable parameters
    │   │   └── HttpClientConfig.java               ← RestTemplate with short timeouts
    │   ├── controller/
    │   │   └── ClusterHealthController.java        ← All REST endpoints
    │   └── service/
    │       ├── ClusterCoordinator.java             ← ZooKeeper connection & registration
    │       ├── NodeHealthChecker.java              ← Periodic heartbeat poller
    │       ├── LoadBalancer.java                   ← Round-robin routing + failover
    │       └── DataSyncService.java                ← Transaction recovery on node rejoin
    └── resources/
        └── application.properties                 ← Node ID, peers, ZK host, thresholds
```

---

## How It Works

### 1. Cluster Membership via ZooKeeper

When the service starts, `ClusterCoordinator` connects to ZooKeeper and creates an **ephemeral znode** at `/payment-cluster/nodes/<nodeId>`. Ephemeral znodes are automatically deleted by ZooKeeper when the session expires — meaning if a node crashes or loses its network connection, ZooKeeper removes its znode within one session timeout window (5 000 ms by default).

```
Startup                         ZooKeeper
  │                                │
  │── new ZooKeeper(host, 5000) ──►│
  │   (CountDownLatch waits for    │
  │    SyncConnected event)        │
  │◄─ SyncConnected ───────────────│
  │                                │
  │── createPathIfNotExists() ────►│  /payment-cluster
  │                                │  /payment-cluster/nodes
  │── create(EPHEMERAL) ──────────►│  /payment-cluster/nodes/node1
  │                                │   (disappears on disconnect)
  │                                │
  │── getActiveNodes() ───────────►│  returns: ["node1", "node2", "node3"]
```

`ClusterCoordinator` also implements `Watcher` to react to `Disconnected` and `SyncConnected` events, keeping its local `connected` flag accurate for the status endpoint.

---

### 2. Node Health Checking

`NodeHealthChecker` runs a `@Scheduled` task every **2 000 ms** that pings `GET /health` on every configured peer. It tracks consecutive failures per peer in `missedHeartbeats` (a `ConcurrentHashMap`) and only flips a node to `DOWN` once the failure count reaches `maxMissedHeartbeats` (default: 3). This prevents transient network blips from triggering false-positive failovers.

```
Every 2 000 ms:

  For each peer in config.getPeers():

    ┌────────────────────────────────────────────────────────────┐
    │  Try GET peer/health                                        │
    │                                                            │
    │  Success → missedHeartbeats[peer] = 0                      │
    │            nodeStatus[peer] = true (UP)                    │
    │            log "Node is now UP" if it was previously DOWN   │
    │                                                            │
    │  Failure → missedHeartbeats[peer]++                        │
    │            if count >= maxMissedHeartbeats (3):            │
    │              nodeStatus[peer] = false (DOWN)               │
    │              log "Node is DOWN after N missed heartbeats"   │
    └────────────────────────────────────────────────────────────┘
```

The 2-second HTTP timeout on `RestTemplate` means a hung peer is detected within 2 s, and declared DOWN after 3 × 2 s = **6 seconds of sustained unresponsiveness**.

---

### 3. Load Balancing with Automatic Failover

`LoadBalancer` routes every incoming payment using **round-robin** selection over the current set of healthy nodes. The `AtomicInteger` counter increments atomically on every request, so concurrent calls are distributed evenly across peers without locking.

```
POST /fault/payment  arrives
         │
         ▼
  healthChecker.getHealthyNodes()
         │
         ├── empty? → return null (503 to caller)
         │
         └── [node2, node3]
                  │
                  │  roundRobinIndex.getAndIncrement() % 2
                  ▼
            target = node2
                  │
                  │  POST node2/payment
                  │
                  ├── 2xx → return result ✓
                  │
                  └── exception / non-2xx
                           │
                           ▼
                     retryWithNextNode()
                           │
                      try node3/payment
                           │
                           ├── 2xx → return result ✓
                           └── all nodes exhausted → return null (503)
```

Failover is **synchronous and in-line** — the caller gets a response as soon as any healthy node accepts the payment, with no circuit-breaker delay or retry queue.

---

### 4. Post-Recovery Data Sync

When a previously failed node restarts, it may have missed transactions that were processed by its peers. Calling `POST /recovery/sync` triggers `DataSyncService.syncFromCluster()`, which:

1. Iterates through currently healthy nodes (as reported by `NodeHealthChecker`).
2. Calls `GET <healthyNode>/transactions` on the first reachable peer to fetch its full transaction list.
3. For each returned transaction, attempts `putIfAbsent` into the local `ConcurrentHashMap` — only inserting entries that don't already exist locally.
4. Returns the count of newly recovered transactions.

```
POST /recovery/sync
        │
        ▼
  for each healthyNode:
        │
        │── GET healthyNode/transactions ─────────────────────►│
        │◄─ [ {txn1}, {txn2}, ... ] ──────────────────────────│
        │
        │  for each transaction:
        │    localTransactions.putIfAbsent(txId, tx)   ← no duplicates
        │
        └── break (first reachable peer is enough)

  return { status: "completed", recoveredTransactions: N }
```

Newly routed payments are also stored locally via `dataSyncService.storeTransaction()` immediately after a successful route, so the local store stays warm even without a recovery sync call.

---

### 5. End-to-End Payment Routing Flow

```
Payment Client
      │
      │  POST /fault/payment  { PaymentTransaction }
      ▼
ClusterHealthController.routePayment()
      │
      ▼
LoadBalancer.routePayment()
      │
      ├── getHealthyNodes() → [node2, node3]
      │
      ├── round-robin pick → node2
      │
      │  POST node2/payment
      │
      ├── success → PaymentTransaction (processed)
      │
      └── failure → retryWithNextNode() → node3
                          │
                          └── success → PaymentTransaction (processed)

      │  (back in controller)
      │
      ▼
dataSyncService.storeTransaction(result)  ← persist locally
      │
      ▼
ResponseEntity.ok(result)   → 200 to caller
(or 503 if all nodes failed)
```

---

## Configuration

All configuration lives in `application.properties`:

```properties
# Which port this node listens on
server.port=8081

# Unique identifier for this node in the cluster
fault-tolerance.node-id=node1

# The other nodes in the cluster to monitor and route to
fault-tolerance.peers=http://localhost:8082,http://localhost:8083

# ZooKeeper connection string
fault-tolerance.zookeeper-host=localhost:2181

# ZooKeeper session timeout (ms) — ephemeral znodes expire after this
fault-tolerance.zookeeper-session-timeout=5000

# How many consecutive missed heartbeats before a node is declared DOWN
fault-tolerance.max-missed-heartbeats=3
```

For nodes 2 and 3, override at startup:

```bash
# Node 2
--server.port=8082
--fault-tolerance.node-id=node2
--fault-tolerance.peers=http://localhost:8081,http://localhost:8083

# Node 3
--server.port=8083
--fault-tolerance.node-id=node3
--fault-tolerance.peers=http://localhost:8081,http://localhost:8082
```

---

## API Reference

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Returns node health and node ID (used as the heartbeat endpoint by peers) |
| `GET` | `/fault/status` | Full cluster view: per-node UP/DOWN, healthy/unhealthy sets, ZooKeeper connection state and active znodes |
| `POST` | `/fault/payment` | Route a `PaymentTransaction` to a healthy peer with automatic failover |
| `POST` | `/recovery/sync` | Pull missing transactions from a healthy peer after this node recovers |

### Check Cluster Status

```http
GET /fault/status
```

**Response:**
```json
{
  "nodeId": "node1",
  "nodeStatus": {
    "http://localhost:8082": true,
    "http://localhost:8083": false
  },
  "healthyNodes": ["http://localhost:8082"],
  "unhealthyNodes": ["http://localhost:8083"],
  "zookeeperConnected": true,
  "zookeeperActiveNodes": ["node1", "node2"]
}
```

### Route a Payment

```http
POST /fault/payment
Content-Type: application/json

{
  "transactionId": "txn-007",
  "amount": 2500.00,
  "currency": "USD",
  "fromAccount": "ACC-A",
  "toAccount": "ACC-B"
}
```

**Success (200):** Processed `PaymentTransaction` returned by the target node.

**Failure (503):** No healthy nodes available to route to.

### Trigger Recovery Sync

```http
POST /recovery/sync
```

**Response:**
```json
{
  "status": "completed",
  "recoveredTransactions": 14
}
```

---

## Running the Module

### Prerequisites

Start a ZooKeeper instance before launching the service:

```bash
# Using Docker
docker run -d --name zookeeper -p 2181:2181 zookeeper:3.9

# Or with a local ZooKeeper installation
bin/zkServer.sh start
```

### Build

```bash
# From the parent project root
mvn clean package -pl fault-tolerance

# Or from within the fault-tolerance directory
mvn clean package
```

### Start all three nodes

```bash
# Terminal 1 — Node 1
java -jar target/fault-tolerance-1.0.0.jar \
  --server.port=8081 \
  --fault-tolerance.node-id=node1 \
  --fault-tolerance.peers=http://localhost:8082,http://localhost:8083

# Terminal 2 — Node 2
java -jar target/fault-tolerance-1.0.0.jar \
  --server.port=8082 \
  --fault-tolerance.node-id=node2 \
  --fault-tolerance.peers=http://localhost:8081,http://localhost:8083

# Terminal 3 — Node 3
java -jar target/fault-tolerance-1.0.0.jar \
  --server.port=8083 \
  --fault-tolerance.node-id=node3 \
  --fault-tolerance.peers=http://localhost:8081,http://localhost:8082
```

### Verify the cluster

```bash
# Check overall cluster health from node 1's perspective
curl http://localhost:8081/fault/status

# Route a payment
curl -X POST http://localhost:8081/fault/payment \
  -H "Content-Type: application/json" \
  -d '{"transactionId":"txn-001","amount":100.00,"currency":"USD","fromAccount":"A1","toAccount":"A2"}'

# Simulate node3 going down, then re-sync it after restart
curl -X POST http://localhost:8083/recovery/sync
```

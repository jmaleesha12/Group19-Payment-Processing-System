# Replication Module — Distributed Payment Processing System

The `replication` module is the **data durability layer** of the distributed payment processing system. It guarantees that every payment transaction is safely written to multiple nodes before being confirmed to the caller, using a **quorum-based replication strategy (N=3, W=2, R=2)**. It also enforces exactly-once processing semantics by rejecting duplicate transactions before they can be written anywhere.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [How It Works](#how-it-works)
  - [1. Quorum-Based Replication (N/W/R)](#1-quorum-based-replication-nwr)
  - [2. Duplicate Detection](#2-duplicate-detection)
  - [3. Write Path — Processing a Payment](#3-write-path--processing-a-payment)
  - [4. Async Replication to Peers](#4-async-replication-to-peers)
  - [5. Replica Acceptance Path](#5-replica-acceptance-path)
  - [6. Fault Tolerance Behaviour](#6-fault-tolerance-behaviour)
- [Configuration](#configuration)
- [API Reference](#api-reference)
- [Running the Cluster](#running-the-cluster)

---

## Overview

The replication module runs as a **3-node cluster**, each node being an independent Spring Boot service on its own port. Any node can receive a payment request. When it does, it becomes the **coordinator** for that write: it saves the transaction locally, fans out replication requests to the other two nodes in parallel, counts acknowledgements, and only marks the transaction `SUCCESS` once the write has landed on at least **2 of the 3 nodes** (the write quorum W=2). If fewer than 2 nodes acknowledge within the timeout window, the transaction is rolled back to `FAILED`.

```
                     ┌──────────────────────────────────────┐
                     │         Payment Client / Gateway      │
                     └───────────────┬──────────────────────┘
                                     │  POST /payment
                                     ▼
                     ┌──────────────────────────────────────┐
                     │       Coordinator  (node1)  :8091     │
                     │                                       │
                     │  1. DuplicateChecker — reject if seen │
                     │  2. Save locally (PaymentRepository)  │
                     │  3. Fan-out to replicas (async)       │
                     │  4. Count acks — commit or rollback   │
                     └──────────┬───────────────┬───────────┘
              POST /replicate    │               │  POST /replicate
                                 ▼               ▼
              ┌──────────────────────┐  ┌──────────────────────┐
              │  Replica  node2:8092 │  │  Replica  node3:8093 │
              │  acceptReplication() │  │  acceptReplication() │
              │  DuplicateChecker    │  │  DuplicateChecker    │
              │  PaymentRepository   │  │  PaymentRepository   │
              └──────────────────────┘  └──────────────────────┘
```

---

## Architecture

### Microservice Architecture

The replication module is one service within a larger **multi-module Maven project** (`distributed-payment-system`). It depends on the `common` module for shared domain models (`PaymentTransaction`, `PaymentStatus`).

```
distributed-payment-system/           ← Parent Maven project
├── common/                           ← Shared models (PaymentTransaction, PaymentStatus)
└── replication/                      ← This module — quorum replication service
    ├── config/                       ← ReplicaConfig, HttpConfig
    ├── controller/                   ← REST endpoints (payment, replicate, status)
    ├── repository/                   ← In-memory PaymentRepository
    └── service/                      ← QuorumManager (write/read path), DuplicateChecker
```

### Internal Component Architecture

```
┌───────────────────────────────────────────────────────────────────────┐
│                         Replication Module                            │
│                                                                       │
│  ┌────────────────────────────────────────────────────────────────┐   │
│  │                  ReplicaController (REST)                       │   │
│  │  GET /health    POST /payment    POST /replicate                │   │
│  │  GET /transactions    GET /replication/status                   │   │
│  └────────────────────────┬───────────────────────────────────────┘   │
│                           │                                            │
│           ┌───────────────▼──────────────────────┐                    │
│           │            QuorumManager              │                    │
│           │  • Coordinates the full write path    │                    │
│           │  • Fans out async replication RPCs    │                    │
│           │  • Counts acks, commits or rolls back │                    │
│           │  • Handles incoming replica writes    │                    │
│           └───────┬──────────────┬───────────────┘                    │
│                   │              │                                     │
│   ┌───────────────▼──┐    ┌──────▼───────────────────────────┐        │
│   │  DuplicateChecker│    │         PaymentRepository         │        │
│   │  ConcurrentHashMap    │  ConcurrentHashMap<String, Txn>  │        │
│   │  putIfAbsent()   │    │  save() / findById() / update()  │        │
│   │  exactly-once    │    │  findAll()                        │        │
│   │  semantics       │    └──────────────────────────────────┘        │
│   └──────────────────┘                                                 │
│                                                                        │
│  ┌──────────────────────┐   ┌──────────────────────────────────────┐  │
│  │    ReplicaConfig      │   │            HttpConfig                │  │
│  │  N=3, W=2, R=2        │   │  RestTemplate: 3s connect / 5s read  │  │
│  │  replicas, timeout    │   │  timeout per replication RPC         │  │
│  └──────────────────────┘   └──────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────┘
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17+ |
| Framework | Spring Boot |
| Build Tool | Apache Maven (multi-module) |
| HTTP Server | Spring Web (embedded Tomcat) |
| Inter-node Replication | Spring `RestTemplate` (async via `CompletableFuture`) |
| Replication Model | Quorum-based (N=3, W=2, R=2) |
| Storage | In-memory `ConcurrentHashMap` (via `PaymentRepository`) |
| Duplicate Detection | `ConcurrentHashMap.putIfAbsent()` (lock-free, atomic) |
| Async Coordination | `CompletableFuture.supplyAsync()` + `future.get(timeout)` |
| Observability | Spring Boot Actuator |
| Boilerplate Reduction | Lombok (`@Data`, `@Slf4j`, `@RequiredArgsConstructor`) |
| Thread Safety | `ConcurrentHashMap`, `AtomicInteger`, `CompletableFuture` |

---

## Project Structure

```
replication/
├── pom.xml
└── src/main/
    ├── java/com/payment/replication/
    │   ├── DataReplicationApp.java                     ← Spring Boot entry point
    │   ├── config/
    │   │   ├── ReplicaConfig.java                      ← Quorum params (N, W, R, replicas, timeout)
    │   │   └── HttpConfig.java                         ← RestTemplate with replication timeouts
    │   ├── controller/
    │   │   └── ReplicaController.java                  ← All REST endpoints
    │   ├── repository/
    │   │   └── PaymentRepository.java                  ← Thread-safe in-memory store
    │   └── service/
    │       ├── QuorumManager.java                      ← Core quorum write/replicate logic
    │       └── DuplicateChecker.java                   ← Exactly-once transaction guard
    └── resources/
        └── application.properties                     ← Node ID, quorum settings, replica URLs
```

---

## How It Works

### 1. Quorum-Based Replication (N/W/R)

The module uses the classic **NWR quorum model** to balance durability and availability:

| Parameter | Value | Meaning |
|---|---|---|
| **N** | 3 | Total number of replica nodes in the cluster |
| **W** | 2 | Minimum nodes that must acknowledge a write before it is committed |
| **R** | 2 | Minimum nodes that must agree for a read to be considered valid |

With W=2 and N=3, the system can tolerate **1 node failure** on the write path — the coordinator plus one surviving replica is enough to reach quorum. Because W + R > N (2 + 2 > 3), every read is guaranteed to overlap with at least one node that participated in the most recent write, ensuring strong read-after-write consistency.

```
N = 3 nodes total
W = 2 write quorum   →  1 node failure tolerated on writes
R = 2 read quorum    →  W + R = 4 > N = 3  →  always reads latest write
```

---

### 2. Duplicate Detection

`DuplicateChecker` uses `ConcurrentHashMap.putIfAbsent()` — a single atomic operation — to enforce exactly-once semantics across concurrent payment submissions. If two threads race to submit the same `transactionId`, exactly one will get `null` back (first write wins) and the other will see the existing value and be rejected immediately, before any storage or replication happens.

```
Thread A: putIfAbsent("txn-001", TRUE)  → null      → NOT a duplicate → proceed
Thread B: putIfAbsent("txn-001", TRUE)  → TRUE       → IS a duplicate  → return 409

No locks needed — ConcurrentHashMap guarantees atomicity of putIfAbsent.
```

If the overall write ultimately fails to reach quorum, `DuplicateChecker.remove()` clears the entry so the transaction can be retried by the caller.

---

### 3. Write Path — Processing a Payment

This is the full lifecycle of a payment from arrival to confirmed commit:

```
POST /payment  { PaymentTransaction }
        │
        ▼
ReplicaController.processPayment()
        │
        ├── assign transactionId (if null)
        ├── assign createdTimestamp (if 0)
        │
        ▼
QuorumManager.processPayment()
        │
        ├── DuplicateChecker.isDuplicate(txId)
        │       YES → status = DUPLICATE → return 409
        │       NO  → continue
        │
        ├── status = PROCESSING
        ├── processedByNode = this node ID
        ├── PaymentRepository.save(transaction)     ← local write (counts as ack #1)
        │
        ├── replicateToQuorum(transaction)          ← async fan-out to replicas
        │       returns: number of replica acks
        │
        ├── totalAcks = replicaAcks + 1             ← include self
        │
        ├── totalAcks >= writeQuorum (2)?
        │       YES → status = SUCCESS
        │             log "committed with N acks"
        │       NO  → status = FAILED
        │             DuplicateChecker.remove(txId)  ← allow retry
        │
        ├── PaymentRepository.update(transaction)   ← persist final status
        │
        └── return transaction

HTTP response:
  SUCCESS  → 200 OK
  DUPLICATE → 409 Conflict
  FAILED   → 503 Service Unavailable
```

---

### 4. Async Replication to Peers

`replicateToQuorum()` fires one `CompletableFuture` per configured replica, all in parallel. Each future calls `POST <replicaUrl>/replicate` and reports back `true` on a 2xx response or `false` on any exception or timeout. The coordinator then collects results sequentially, waiting up to `replicationTimeout` (5 000 ms) per future.

```
replicateToQuorum(transaction)
        │
        ├── for each replica in [node2, node3]:
        │       CompletableFuture.supplyAsync(() ->
        │           POST replicaUrl/replicate { transaction }
        │           return response.is2xx()
        │       )
        │
        ├── futures = [futureNode2, futureNode3]  ← running in parallel
        │
        ├── for each future:
        │       future.get(5000ms timeout)
        │       true  → successCount++
        │       false / timeout / exception → skip
        │
        └── return successCount   (0, 1, or 2)

Parallel execution means total replication time ≈ slowest single replica,
not the sum of all replicas.
```

---

### 5. Replica Acceptance Path

When a node receives `POST /replicate` (i.e., it is acting as a replica, not a coordinator), `QuorumManager.acceptReplication()` handles it:

```
POST /replicate  { PaymentTransaction }
        │
        ▼
QuorumManager.acceptReplication()
        │
        ├── DuplicateChecker.exists(txId)?
        │       YES → return existing record from PaymentRepository
        │             (idempotent — safe to receive same txn twice)
        │       NO  → DuplicateChecker.isDuplicate(txId)   ← register it
        │             PaymentRepository.save(transaction)   ← store it
        │
        └── return saved transaction → 200 OK to coordinator
```

The idempotency check (`exists` before `isDuplicate`) means a coordinator can safely retry replication to a replica without creating duplicate entries.

---

### 6. Fault Tolerance Behaviour

| Scenario | Behaviour |
|---|---|
| 1 replica node is down | Coordinator still reaches quorum (self + 1 surviving replica = 2 acks ≥ W=2). Transaction commits successfully. |
| Both replica nodes are down | Only 1 ack (self). totalAcks < W=2. Transaction marked FAILED, duplicate entry removed so caller can retry later. |
| Replica times out mid-replication | `future.get(5000ms)` throws `TimeoutException`. That replica counts as 0. Other replicas still counted. |
| Duplicate submission (same txId) | `putIfAbsent` atomically rejects at `DuplicateChecker` before any write. Returns 409 immediately. |
| Coordinator fails after local write but before quorum | Replica nodes hold the transaction but the coordinator's local store has it too. A restarted coordinator will not re-process it if the txId is re-submitted — the caller gets a 409 if it retries, unless the coordinator's in-memory state was lost on restart. |
| Network partition | Minority-side coordinator cannot reach quorum and returns FAILED. Majority side continues normally. |

---

## Configuration

All configuration lives in `application.properties`:

```properties
# Which port this node listens on
server.port=8091

# Unique identifier for this node
replication.node-id=node1

# Quorum parameters
replication.total-nodes=3
replication.write-quorum=2
replication.read-quorum=2

# The other nodes in the cluster that receive replicated writes
replication.replicas=http://localhost:8092,http://localhost:8093

# Max time (ms) to wait for each replica to acknowledge before counting as failed
replication.replication-timeout=5000
```

For nodes 2 and 3, override at startup:

```bash
# Node 2
--server.port=8092
--replication.node-id=node2
--replication.replicas=http://localhost:8091,http://localhost:8093

# Node 3
--server.port=8093
--replication.node-id=node3
--replication.replicas=http://localhost:8091,http://localhost:8092
```

---

## API Reference

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Returns node health and node ID |
| `GET` | `/replication/status` | Returns quorum config (W, R) and local transaction count |
| `POST` | `/payment` | Submit a payment for quorum-replicated write (coordinator path) |
| `POST` | `/replicate` | Internal — accept a replicated write from a coordinator (replica path) |
| `GET` | `/transactions` | List all transactions stored on this node |

### Submit a Payment

```http
POST /payment
Content-Type: application/json

{
  "transactionId": "txn-001",
  "amount": 750.00,
  "currency": "USD",
  "fromAccount": "ACC-100",
  "toAccount": "ACC-200"
}
```

**Success — quorum reached (200):**
```json
{
  "transactionId": "txn-001",
  "status": "SUCCESS",
  "processedByNode": "node1",
  "createdTimestamp": 1711532400000
}
```

**Duplicate transaction (409):**
```json
{
  "transactionId": "txn-001",
  "status": "DUPLICATE"
}
```

**Quorum not reached (503):**
```json
{
  "transactionId": "txn-001",
  "status": "FAILED"
}
```

### Check Replication Status

```http
GET /replication/status
```

```json
{
  "nodeId": "node1",
  "writeQuorum": 2,
  "readQuorum": 2,
  "transactionCount": 47
}
```

---

## Running the Cluster

### Build

```bash
# From the parent project root
mvn clean package -pl replication

# Or from within the replication directory
mvn clean package
```

### Start all three nodes

```bash
# Terminal 1 — Node 1
java -jar target/replication-1.0.0.jar \
  --server.port=8091 \
  --replication.node-id=node1 \
  --replication.replicas=http://localhost:8092,http://localhost:8093

# Terminal 2 — Node 2
java -jar target/replication-1.0.0.jar \
  --server.port=8092 \
  --replication.node-id=node2 \
  --replication.replicas=http://localhost:8091,http://localhost:8093

# Terminal 3 — Node 3
java -jar target/replication-1.0.0.jar \
  --server.port=8093 \
  --replication.node-id=node3 \
  --replication.replicas=http://localhost:8091,http://localhost:8092
```

### Verify the cluster

```bash
# Check replication status on each node
curl http://localhost:8091/replication/status
curl http://localhost:8092/replication/status
curl http://localhost:8093/replication/status

# Submit a payment (any node can act as coordinator)
curl -X POST http://localhost:8091/payment \
  -H "Content-Type: application/json" \
  -d '{"transactionId":"txn-001","amount":750.00,"currency":"USD","fromAccount":"ACC-100","toAccount":"ACC-200"}'

# Verify the transaction was replicated to all nodes
curl http://localhost:8091/transactions
curl http://localhost:8092/transactions
curl http://localhost:8093/transactions

# Test duplicate rejection
curl -X POST http://localhost:8091/payment \
  -H "Content-Type: application/json" \
  -d '{"transactionId":"txn-001","amount":750.00,"currency":"USD","fromAccount":"ACC-100","toAccount":"ACC-200"}'
# Expected: 409 Conflict
```

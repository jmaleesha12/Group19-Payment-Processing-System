# Consensus Module — Distributed Payment Processing System

The `consensus` module is the fault-tolerance backbone of the distributed payment processing system. It implements the **Raft consensus algorithm** to guarantee that every payment transaction is agreed upon by a majority of nodes before being considered committed — preventing double-processing, split-brain scenarios, and data loss when nodes crash or become temporarily unreachable.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [How It Works](#how-it-works)
  - [1. Node States](#1-node-states)
  - [2. Leader Election](#2-leader-election)
  - [3. Log Replication](#3-log-replication)
  - [4. Payment Transaction Flow](#4-payment-transaction-flow)
  - [5. Fault Tolerance](#5-fault-tolerance)
- [Configuration](#configuration)
- [API Reference](#api-reference)
- [Running the Cluster](#running-the-cluster)

---

## Overview

The consensus module runs as a **3-node cluster**, each node being an independent Spring Boot service. Nodes communicate with each other over HTTP using Raft RPCs (Remote Procedure Calls). Only the **elected Leader** accepts and processes incoming payment transactions — the other nodes act as **Followers** that replicate the leader's log. If the leader goes down, the remaining nodes automatically elect a new leader within seconds.

```
                          ┌─────────────────────────────────────┐
                          │       Payment Processing System      │
                          └──────────────┬──────────────────────┘
                                         │ POST /raft/submit
                                         ▼
                        ┌────────────────────────────┐
                        │       LEADER  (node1)       │  :8071
                        │    ConsensusNode (LEADER)   │
                        └────────┬──────────┬─────────┘
                 AppendEntries   │          │  AppendEntries
                  (heartbeat +   │          │  (heartbeat +
                   log entries)  │          │   log entries)
                                 ▼          ▼
              ┌──────────────────┐        ┌──────────────────┐
              │  FOLLOWER (node2)│        │  FOLLOWER (node3)│
              │     :8072        │        │     :8073        │
              └──────────────────┘        └──────────────────┘
```

---

## Architecture

### Microservice Architecture

The consensus module is one service within a larger **multi-module Maven project** (`distributed-payment-system`). It depends on the `common` module for shared domain models (`PaymentTransaction`, `PaymentStatus`).

```
distributed-payment-system/          ← Parent Maven project
├── common/                          ← Shared models (PaymentTransaction, PaymentStatus)
└── consensus/                       ← This module — Raft consensus service
    ├── config/                      ← RaftConfig, RpcClientConfig
    ├── controller/                  ← REST endpoints (Raft RPCs + payment submission)
    ├── model/                       ← Raft message models (election, replication)
    └── service/                     ← ConsensusNode (Raft logic), ConsensusLog (replicated log)
```

### Internal Component Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                         Consensus Module                         │
│                                                                  │
│   ┌────────────────────────────────────────────────────────┐    │
│   │               ConsensusController (REST)               │    │
│   │  GET /health  POST /raft/vote  POST /raft/append       │    │
│   │  POST /raft/submit  GET /raft/status  GET /raft/transactions│
│   └────────────────────┬───────────────────────────────────┘    │
│                        │                                         │
│        ┌───────────────▼────────────────────┐                   │
│        │         ConsensusNode              │                   │
│        │  • Leader election logic           │                   │
│        │  • Heartbeat scheduling            │                   │
│        │  • Election timeout tracking       │                   │
│        │  • Peer RPC dispatch (RestTemplate)│                   │
│        │  • Transaction submission          │                   │
│        └───────────────┬────────────────────┘                   │
│                        │                                         │
│        ┌───────────────▼────────────────────┐                   │
│        │         ConsensusLog               │                   │
│        │  • Thread-safe replicated log      │                   │
│        │  • appendEntries / getEntry        │                   │
│        │  • commitIndex tracking            │                   │
│        │  • Applied transaction store       │                   │
│        └────────────────────────────────────┘                   │
│                                                                  │
│   ┌──────────────┐    ┌──────────────────────────────────┐      │
│   │  RaftConfig  │    │        RpcClientConfig            │      │
│   │  (timeouts,  │    │   (RestTemplate with 500ms        │      │
│   │   peers,     │    │    connect / 1000ms read timeout) │      │
│   │   node ID)   │    └──────────────────────────────────┘      │
│   └──────────────┘                                               │
└──────────────────────────────────────────────────────────────────┘
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17+ |
| Framework | Spring Boot |
| Build Tool | Apache Maven (multi-module) |
| HTTP Server | Spring Web (embedded Tomcat) |
| Inter-node Communication | Spring `RestTemplate` (synchronous HTTP RPC) |
| Scheduling | Spring `@Scheduled` (election timer, heartbeat loop) |
| Observability | Spring Boot Actuator |
| Boilerplate Reduction | Lombok (`@Data`, `@Builder`, `@Slf4j`) |
| Consensus Algorithm | Raft (custom implementation) |
| Thread Safety | `ReentrantReadWriteLock`, `AtomicInteger`, `volatile`, `ConcurrentHashMap` |

---

## Project Structure

```
consensus/
├── pom.xml
└── src/main/
    ├── java/com/payment/consensus/
    │   ├── LeaderElectionApp.java              ← Spring Boot entry point
    │   ├── config/
    │   │   ├── RaftConfig.java                 ← Raft tuning parameters
    │   │   └── RpcClientConfig.java            ← RestTemplate bean with timeouts
    │   ├── controller/
    │   │   └── ConsensusController.java        ← All REST endpoints
    │   ├── model/
    │   │   ├── ServerState.java                ← FOLLOWER / CANDIDATE / LEADER enum
    │   │   ├── ElectionRequest.java            ← RequestVote RPC payload
    │   │   ├── ElectionResponse.java           ← RequestVote RPC response
    │   │   ├── LogReplicationRequest.java      ← AppendEntries RPC payload
    │   │   ├── LogReplicationResponse.java     ← AppendEntries RPC response
    │   │   └── RaftLogEntry.java               ← A single log entry (term + index + PaymentTransaction)
    │   └── service/
    │       ├── ConsensusNode.java              ← Core Raft state machine
    │       └── ConsensusLog.java               ← Thread-safe replicated log
    └── resources/
        └── application.properties             ← Node ID, peers, timeouts
```

---

## How It Works

### 1. Node States

Every node is always in exactly one of three states:

| State | Behaviour |
|---|---|
| `FOLLOWER` | Passively receives heartbeats and log entries from the leader. If it hears nothing for `electionTimeout` milliseconds, it promotes itself to CANDIDATE. |
| `CANDIDATE` | Requests votes from all peers. If it wins a majority, it becomes LEADER. If it discovers a higher term, it steps back to FOLLOWER. |
| `LEADER` | Sends heartbeats every 500 ms. Accepts payment transactions, appends them to the log, replicates them to followers, and commits once a majority confirms. |

---

### 2. Leader Election

Election timeouts are **randomised** between `electionTimeoutMin` (1500 ms) and `electionTimeoutMax` (3000 ms) to break ties. The process:

```
FOLLOWER                    CANDIDATE                    PEERS
   │                            │                          │
   │── timeout fires ──────────►│                          │
   │                            │── POST /raft/vote ──────►│
   │                            │◄─ voteGranted: true ─────│
   │                            │   (from majority)        │
   │                            │── becomeLeader() ────────┤
   │                            │                          │
   │◄──── AppendEntries ────────│ (heartbeat, empty log)   │
   │      (heartbeat)           │                          │
```

**Vote granting rules (from `handleVoteRequest`):**
1. The candidate's term must be ≥ the voter's current term.
2. The voter must not have already voted in this term (or must be voting for the same candidate).
3. The candidate's log must be at least as up-to-date as the voter's log (`lastLogTerm` and `lastLogIndex` comparison).

A node needs **⌊(N/2)⌋ + 1** votes to win (majority of the 3-node cluster = 2 votes).

---

### 3. Log Replication

The leader replicates entries to followers using the **AppendEntries RPC** (`POST /raft/append`). The same RPC with an empty `entries` list acts as a heartbeat to reset follower election timeouts.

```
LEADER                          FOLLOWER
  │                                │
  │── POST /raft/append ──────────►│
  │   { term, leaderId,            │   1. Check term validity
  │     prevLogIndex,              │   2. Verify prevLogIndex / prevLogTerm match
  │     prevLogTerm,               │   3. Append new entries (truncating conflicts)
  │     entries: [...],            │   4. Advance commitIndex to min(leaderCommit, lastLogIndex)
  │     leaderCommit }             │   5. Apply committed entries to appliedTransactions
  │◄─ { success: true,             │
  │     matchIndex: N } ───────────│
  │                                │
  │── updateLeaderCommitIndex() ──►│ (leader advances commit when majority match)
```

The leader tracks `nextIndex` and `matchIndex` per peer. On a failed replication, it decrements `nextIndex` and retries — this is how Raft recovers a lagging or rejoining follower by backfilling its log.

---

### 4. Payment Transaction Flow

This is the end-to-end path a payment takes through the consensus module:

```
Client / Payment Service
        │
        │  POST /raft/submit  { PaymentTransaction }
        ▼
┌──────────────────────────────────────────┐
│  ConsensusController.submitPayment()     │
│  • If not leader → 503 + leaderId hint   │
│  • Generates UUID if missing             │
└────────────────┬─────────────────────────┘
                 │
                 ▼
┌──────────────────────────────────────────┐
│  ConsensusNode.submitTransaction()       │
│  1. Set status = PROCESSING              │
│  2. Tag processedByNode = this node ID   │
│  3. consensusLog.append(term, txn)       │   ← Appended to leader's log
│  4. sendLogReplication() to all peers   │   ← AppendEntries to followers
│  5. Poll commitIndex every 50 ms        │   ← Wait for majority to confirm
│     (up to 20 attempts = 1 second)      │
│  6. status = SUCCESS / FAILED           │
└────────────────┬─────────────────────────┘
                 │
                 ▼
        Response to caller
        { success, transaction }
```

**Commit guarantee:** A transaction is only marked `SUCCESS` once the leader's `commitIndex` has advanced to include the transaction's log index — meaning **at least 2 of the 3 nodes** have written it to their log.

---

### 5. Fault Tolerance

| Scenario | Behaviour |
|---|---|
| Leader crashes | Followers time out (1.5–3 s), hold a new election, elect a surviving node as leader. |
| Follower crashes | Leader keeps replicating. As long as 1 follower is alive (2 of 3 nodes), transactions still commit. |
| Network partition (minority side) | Minority-side followers cannot form a majority and cannot elect a new leader. The majority side continues normally. |
| Follower rejoins after downtime | Leader backfills the lagging log by decrementing `nextIndex` until entries match, then replays missing entries. |
| Non-leader receives payment request | Returns HTTP 503 with the known `leaderId` so the caller can redirect. |

---

## Configuration

All configuration lives in `application.properties`:

```properties
# Which port this node listens on
server.port=8071

# Unique identifier for this node
consensus.node-id=node1

# The other two nodes in the cluster
consensus.peers=http://localhost:8072,http://localhost:8073

# Election timeout window (randomised between min and max, in ms)
consensus.election-timeout-min=1500
consensus.election-timeout-max=3000

# How often the leader sends heartbeats (ms)
consensus.heartbeat-interval=500

# RPC call timeout (ms)
consensus.rpc-timeout=1000
```

For nodes 2 and 3, override these values at startup:

```bash
# Node 2
--server.port=8072
--consensus.node-id=node2
--consensus.peers=http://localhost:8071,http://localhost:8073

# Node 3
--server.port=8073
--consensus.node-id=node3
--consensus.peers=http://localhost:8071,http://localhost:8072
```

---

## API Reference

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Returns node health, node ID, and current role |
| `GET` | `/raft/status` | Full Raft state: term, state, votedFor, leaderId, log size, commitIndex |
| `POST` | `/raft/vote` | Internal — RequestVote RPC (peer-to-peer only) |
| `POST` | `/raft/append` | Internal — AppendEntries RPC / heartbeat (peer-to-peer only) |
| `POST` | `/raft/submit` | Submit a `PaymentTransaction` for consensus (leader only) |
| `GET` | `/raft/transactions` | List all committed and applied payment transactions |

### Submit a Payment Transaction

```http
POST /raft/submit
Content-Type: application/json

{
  "transactionId": "txn-001",
  "amount": 1500.00,
  "currency": "USD",
  "sourceAccount": "ACC-100",
  "destinationAccount": "ACC-200"
}
```

**Success response (200):**
```json
{
  "success": true,
  "transaction": {
    "transactionId": "txn-001",
    "status": "SUCCESS",
    "processedByNode": "node1",
    "createdTimestamp": 1711532400000
  }
}
```

**Non-leader response (503):**
```json
{
  "success": false,
  "error": "Not the leader",
  "leaderId": "node2"
}
```

---

## Running the Cluster

### Build

```bash
# From the parent project root
mvn clean package -pl consensus

# Or from within the consensus directory
mvn clean package
```

### Start all three nodes

```bash
# Terminal 1 — Node 1 (likely becomes first leader)
java -jar target/consensus-1.0.0.jar \
  --server.port=8071 \
  --consensus.node-id=node1 \
  --consensus.peers=http://localhost:8072,http://localhost:8073

# Terminal 2 — Node 2
java -jar target/consensus-1.0.0.jar \
  --server.port=8072 \
  --consensus.node-id=node2 \
  --consensus.peers=http://localhost:8071,http://localhost:8073

# Terminal 3 — Node 3
java -jar target/consensus-1.0.0.jar \
  --server.port=8073 \
  --consensus.node-id=node3 \
  --consensus.peers=http://localhost:8071,http://localhost:8072
```

### Verify the cluster

```bash
# Check which node is the leader
curl http://localhost:8071/raft/status
curl http://localhost:8072/raft/status
curl http://localhost:8073/raft/status

# Submit a payment to the leader (adjust port to whichever node is leader)
curl -X POST http://localhost:8071/raft/submit \
  -H "Content-Type: application/json" \
  -d '{"amount": 500.00, "currency": "USD", "sourceAccount": "A1", "destinationAccount": "A2"}'

# View all committed transactions
curl http://localhost:8071/raft/transactions
```

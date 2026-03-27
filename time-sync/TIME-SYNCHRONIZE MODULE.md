# Time Synchronization Module — Distributed Payment Processing System

The `time-sync` module is the **clock accuracy layer** of the distributed payment processing system. It solves one of the fundamental challenges in distributed systems: nodes on separate physical machines have independent clocks that drift apart over time, making it impossible to reliably order events (like payment transactions) by timestamp alone. This module tackles the problem with a **dual-clock strategy** — a physical NTP clock for wall-clock accuracy and a Lamport logical clock for strict causal event ordering — combined into a single `HybridTimestamp` used across the system.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [How It Works](#how-it-works)
  - [1. The Problem — Clock Drift in Distributed Systems](#1-the-problem--clock-drift-in-distributed-systems)
  - [2. Physical Clock Sync via NTP](#2-physical-clock-sync-via-ntp)
  - [3. NTP Offset Calculation — The Four-Timestamp Algorithm](#3-ntp-offset-calculation--the-four-timestamp-algorithm)
  - [4. Lamport Logical Clock](#4-lamport-logical-clock)
  - [5. Hybrid Timestamp — Combining Both Clocks](#5-hybrid-timestamp--combining-both-clocks)
  - [6. Event Recording Flow](#6-event-recording-flow)
- [Configuration](#configuration)
- [API Reference](#api-reference)
- [Running the Service](#running-the-service)

---

## Overview

The module runs as a standalone Spring Boot service on every node in the payment cluster. On startup it immediately syncs with an NTP server (`pool.ntp.org`) over raw UDP, calculates the local clock's offset from true network time, and begins serving corrected timestamps to the rest of the system. A scheduled job re-syncs every 60 seconds to compensate for ongoing clock drift. In parallel, a Lamport logical clock tracks causal ordering of events — regardless of what the wall clock says, any event that causally follows another will always carry a higher logical timestamp.

```
                    ┌────────────────────────────────────────────┐
                    │            time-sync service               │
                    │                                            │
                    │  ┌──────────────────────────────────────┐  │
                    │  │      NetworkTimeService               │  │
                    │  │  Raw UDP NTP request → pool.ntp.org   │  │
                    │  │  Four-timestamp offset calculation    │  │
                    │  │  @PostConstruct + @Scheduled/60s      │  │
                    │  └──────────────────────────────────────┘  │
                    │                                            │
                    │  ┌──────────────────────────────────────┐  │
                    │  │         LogicalClock                  │  │
                    │  │  Lamport clock — AtomicLong + CAS     │  │
                    │  │  tick() / send() / receive(t)         │  │
                    │  └──────────────────────────────────────┘  │
                    │                    │                        │
                    │         combined into HybridTimestamp       │
                    │         (physical + logical counter)        │
                    └──────────────────┬─────────────────────────┘
                                       │
                          ┌────────────┼────────────┐
                          ▼            ▼            ▼
                     GET /time/now  POST /time/event  GET /time/status
                     (other payment modules consume these endpoints)
```

---

## Architecture

### Microservice Architecture

The time-sync module is one service within a larger **multi-module Maven project** (`distributed-payment-system`). It depends on the `common` module for the shared `HybridTimestamp` model that is consumed by other modules across the system.

```
distributed-payment-system/           ← Parent Maven project
├── common/                           ← Shared models (HybridTimestamp, PaymentTransaction…)
└── time-sync/                        ← This module — clock sync service
    ├── config/                       ← ClockConfig (NTP server, port, intervals)
    ├── controller/                   ← REST endpoints (time query, event recording, status)
    └── service/                      ← NetworkTimeService (NTP), LogicalClock (Lamport)
```

### Internal Component Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                        Time Sync Module                              │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                  ClockController (REST)                         │  │
│  │  GET /health   GET /time/status   GET /time/now                 │  │
│  │  POST /time/event                                               │  │
│  └────────────────────┬─────────────────────┬──────────────────────┘  │
│                       │                     │                         │
│   ┌───────────────────▼────────────┐  ┌─────▼────────────────────┐   │
│   │      NetworkTimeService        │  │      LogicalClock         │   │
│   │                                │  │                           │   │
│   │  @PostConstruct → syncWithNtp()│  │  AtomicLong timestamp     │   │
│   │  @Scheduled/60s → syncWithNtp()│  │                           │   │
│   │                                │  │  tick()    → increment    │   │
│   │  Raw UDP DatagramSocket        │  │  send()    → tick alias   │   │
│   │  NTP packet (48 bytes, 0x1B)   │  │  receive(t)→ CAS loop:    │   │
│   │  t1, t2, t3, t4 timestamps     │  │   max(local,t) + 1        │   │
│   │  offset = ((t2-t1)+(t3-t4))/2  │  │  getTime() → read current │   │
│   │                                │  │                           │   │
│   │  AtomicLong ntpOffset          │  └───────────────────────────┘   │
│   │  getCorrectedTime()            │                                   │
│   │   = System.currentTimeMillis() │  ┌───────────────────────────┐   │
│   │     + ntpOffset                │  │     HybridTimestamp        │   │
│   └────────────────────────────────┘  │  (from common module)     │   │
│                                       │  physical wall clock +    │   │
│                                       │  logical Lamport counter  │   │
│   ┌──────────────────────────────┐    └───────────────────────────┘   │
│   │        ClockConfig            │                                    │
│   │  ntpServer, ntpPort (123)     │                                    │
│   │  ntpTimeout (5000 ms)         │                                    │
│   │  syncIntervalMs (60 000 ms)   │                                    │
│   └──────────────────────────────┘                                    │
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
| Physical Time Sync | NTP over raw UDP (`DatagramSocket` — no third-party NTP library) |
| Logical Time | Lamport Clock (custom implementation with `AtomicLong` + CAS) |
| Hybrid Timestamp | `HybridTimestamp` from `common` module (physical + logical) |
| Sync Scheduling | Spring `@Scheduled` + `@PostConstruct` |
| Observability | Spring Boot Actuator |
| Boilerplate Reduction | Lombok (`@Data`, `@Slf4j`, `@RequiredArgsConstructor`, `@Getter`) |
| Thread Safety | `AtomicLong`, `volatile`, CAS (`compareAndSet`) loop |

---

## Project Structure

```
time-sync/
├── pom.xml
└── src/main/
    ├── java/com/payment/timesync/
    │   ├── ClockSyncApp.java                         ← Spring Boot entry point
    │   ├── config/
    │   │   └── ClockConfig.java                      ← NTP & sync tuning parameters
    │   ├── controller/
    │   │   └── ClockController.java                  ← All REST endpoints
    │   └── service/
    │       ├── NetworkTimeService.java               ← NTP sync, offset tracking, corrected time
    │       └── LogicalClock.java                     ← Lamport logical clock
    └── resources/
        └── application.properties                   ← Node ID, NTP server, sync interval
```

---

## How It Works

### 1. The Problem — Clock Drift in Distributed Systems

Every physical server has a hardware clock that ticks at a slightly different rate. Over hours or days, two nodes that started synchronized will drift apart — sometimes by hundreds of milliseconds. In a payment processing system this matters enormously:

- A transaction timestamped on node1 at `T` may appear **older** than a transaction on node2 timestamped at `T+1ms` even though it was actually processed first.
- Ordering payments by wall-clock time alone is unreliable and can lead to incorrect sequencing, double-charge detection failures, or audit trail inconsistencies.

The time-sync module addresses this with **two complementary mechanisms**:

| Mechanism | What it fixes |
|---|---|
| NTP physical sync | Corrects each node's wall clock against authoritative internet time, reducing absolute drift to milliseconds |
| Lamport logical clock | Guarantees causal ordering — if event A caused event B, B will always have a higher logical timestamp than A, regardless of wall clock |

---

### 2. Physical Clock Sync via NTP

`NetworkTimeService` implements a full NTP client **from scratch using raw UDP** — no third-party NTP library is used. This gives the payment system direct control over timeout behaviour, packet construction, and offset calculation.

The sync lifecycle:

```
Application startup
        │
        ▼
@PostConstruct → syncWithNtp()          ← immediate sync on first boot
        │
        │  (runs every 60 000 ms)
        ▼
@Scheduled → scheduledSync()
        │
        ▼
calculateNtpOffset()
        │
        ├── open DatagramSocket (UDP)
        ├── setSoTimeout(5000ms)         ← fail fast if NTP unreachable
        ├── InetAddress.getByName("pool.ntp.org")
        ├── build 48-byte NTP request packet (LI=0, VN=3, Mode=3 → byte 0x1B)
        ├── record t1 = System.currentTimeMillis()
        ├── socket.send(requestPacket)
        ├── socket.receive(responsePacket)
        ├── record t4 = System.currentTimeMillis()
        ├── extract t2, t3 from NTP response bytes
        │
        └── offset = ((t2 - t1) + (t3 - t4)) / 2
                │
                ▼
        ntpOffset.set(offset)            ← AtomicLong, thread-safe
        syncSuccessful = true
        lastSyncTime = now
```

If the NTP server is unreachable, `syncSuccessful` is set to `false` and `lastError` captures the message. The previously computed `ntpOffset` is retained, so corrected timestamps continue to be served with the last known good offset rather than falling back to the uncorrected system clock.

---

### 3. NTP Offset Calculation — The Four-Timestamp Algorithm

The offset calculation uses the standard NTP four-timestamp formula, which accounts for network round-trip delay symmetrically:

```
t1  ──── request sent ────────────────────────────────────────────►
                                                              NTP Server
t4  ◄─── response received ──── t3 (response sent) ──── t2 (request received)

Round-trip delay  d = (t4 - t1) - (t3 - t2)
Clock offset      θ = ((t2 - t1) + (t3 - t4)) / 2
```

| Variable | Meaning |
|---|---|
| `t1` | Local time just before the NTP request packet was sent (`System.currentTimeMillis()`) |
| `t2` | Server time when the NTP server received the request (extracted from response bytes 32–39) |
| `t3` | Server time when the NTP server sent its response (extracted from response bytes 40–47) |
| `t4` | Local time just after the NTP response packet was received (`System.currentTimeMillis()`) |

NTP timestamps are 64-bit values: 32 bits of seconds since **1 January 1900** and 32 bits of sub-second fractions. `extractNtpTimestamp()` converts these to Unix milliseconds by subtracting `NTP_EPOCH_OFFSET = 2_208_988_800` seconds (the exact difference between the NTP epoch and the Unix epoch) and scaling the fraction appropriately.

Once `ntpOffset` is known, corrected wall-clock time is simply:

```java
getCorrectedTime() = System.currentTimeMillis() + ntpOffset.get()
```

---

### 4. Lamport Logical Clock

`LogicalClock` implements the classic **Lamport clock** rules using a lock-free `AtomicLong` counter and a Compare-And-Set (CAS) loop. No `synchronized` blocks are needed.

**Three operations:**

| Operation | Rule | Implementation |
|---|---|---|
| `tick()` / `send()` | Increment before sending a message or recording a local event | `timestamp.incrementAndGet()` |
| `receive(t)` / `update(t)` | On receiving a message with timestamp `t`, advance clock to `max(local, t) + 1` | CAS loop — read, compute, attempt set, retry if another thread changed it first |
| `getTime()` | Read the current logical time without advancing it | `timestamp.get()` |

**The CAS loop in `update()` — why it matters:**

```java
while (true) {
    long current = timestamp.get();               // read current value
    long newTime = Math.max(current, received) + 1; // compute next value
    if (timestamp.compareAndSet(current, newTime))  // atomic: set only if unchanged
        break;                                      // success
    // else: another thread changed timestamp; loop and retry with fresh read
}
```

This guarantees that even under heavy concurrent load (multiple payment events arriving simultaneously), the logical clock never goes backwards, never skips a value, and never requires a lock.

**Causal ordering guarantee:**

```
Node1 sends payment-A    → logicalClock.send() returns L=5  → message carries L=5
Node2 receives payment-A → logicalClock.receive(5) → max(3, 5)+1 = 6
Node2 sends payment-B    → logicalClock.send() returns L=7

Result: payment-A (L=5) < payment-B (L=7)
Even if Node2's wall clock was ahead of Node1's, the causal ordering is correct.
```

---

### 5. Hybrid Timestamp — Combining Both Clocks

`ClockController` combines both clocks into a single `HybridTimestamp` (defined in the `common` module) every time a timestamp is issued. This gives consumers of the timestamp two independent ordering dimensions:

| Dimension | Source | Use case |
|---|---|---|
| Physical (wall clock) | `networkTimeService.getCorrectedTime()` | Human-readable timestamps, SLA calculations, audit logs |
| Logical (Lamport) | `logicalClock.tick()` | Causal event ordering, transaction sequencing |

```java
// In ClockController.getCurrentTime():
long logicalTime = logicalClock.tick();                          // advance logical clock
HybridTimestamp ts = HybridTimestamp.nowWithOffset(
    networkTimeService.getOffset(),   // NTP correction
    logicalTime                       // Lamport counter
);
```

The hybrid approach means that even if NTP sync temporarily fails and the physical offset is stale, the logical clock still guarantees correct causal ordering within the cluster.

---

### 6. Event Recording Flow

`POST /time/event` is the integration point for other modules in the payment system. When a payment module sends an event (with or without an incoming logical timestamp), the flow is:

```
POST /time/event
{ "receivedLogicalTime": 42 }        ← message from another node
        │
        ▼
ClockController.recordEvent()
        │
        ├── receivedLogicalTime present?
        │       YES → logicalClock.receive(42)    ← Lamport receive rule
        │             → max(local, 42) + 1
        │       NO  → logicalClock.tick()         ← local event, just increment
        │
        ├── HybridTimestamp.nowWithOffset(ntpOffset, logicalTime)
        │
        └── return { timestamp: HybridTimestamp, nodeId: "node1" }
```

This endpoint is the mechanism by which causal ordering propagates across nodes. When node2 processes an event that was triggered by a message from node1, it passes node1's logical timestamp in `receivedLogicalTime` — and node2's clock advances to be strictly greater than node1's, preserving the happened-before relationship.

---

## Configuration

All configuration lives in `application.properties`:

```properties
# Which port this node listens on
server.port=8061

# Unique identifier for this node
time-sync.node-id=node1

# NTP server to sync physical time against
time-sync.ntp-server=pool.ntp.org
time-sync.ntp-port=123

# Max time to wait for NTP server response (ms)
time-sync.ntp-timeout=5000

# How often to re-sync with NTP to correct ongoing drift (ms)
# Default: 60 000 ms = 1 minute
time-sync.sync-interval-ms=60000
```

For additional nodes, override at startup:

```bash
# Node 2
--server.port=8062
--time-sync.node-id=node2

# Node 3
--server.port=8063
--time-sync.node-id=node3
```

NTP server and sync interval are the same across all nodes — they all independently sync from the same authoritative source, so their corrected clocks converge to the same reference.

---

## API Reference

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Returns node health and node ID |
| `GET` | `/time/status` | Full clock state: NTP offset, sync success flag, Lamport time, corrected wall time |
| `GET` | `/time/now` | Issues a fresh `HybridTimestamp` (increments Lamport clock) |
| `POST` | `/time/event` | Records an event; optionally accepts an incoming logical time to advance Lamport clock causally |

### Get Current Hybrid Timestamp

```http
GET /time/now
```

**Response:**
```json
{
  "physicalTime": 1711532461234,
  "logicalTime": 17,
  "nodeId": "node1"
}
```

### Get Full Clock Status

```http
GET /time/status
```

**Response:**
```json
{
  "nodeId": "node1",
  "ntpOffset": -12,
  "syncSuccessful": true,
  "lamportTime": 17,
  "correctedTime": 1711532461222
}
```

### Record a Local Event

```http
POST /time/event
Content-Type: application/json

{}
```

**Response:**
```json
{
  "timestamp": {
    "physicalTime": 1711532461235,
    "logicalTime": 18
  },
  "nodeId": "node1"
}
```

### Record a Received Event (Causal Ordering)

```http
POST /time/event
Content-Type: application/json

{
  "receivedLogicalTime": 42
}
```

**Response — logical clock advances to max(local, 42) + 1:**
```json
{
  "timestamp": {
    "physicalTime": 1711532461240,
    "logicalTime": 43
  },
  "nodeId": "node1"
}
```

---

## Running the Service

### Build

```bash
# From the parent project root
mvn clean package -pl time-sync

# Or from within the time-sync directory
mvn clean package
```

### Start the service

```bash
# Node 1
java -jar target/time-sync-1.0.0.jar \
  --server.port=8061 \
  --time-sync.node-id=node1

# Node 2
java -jar target/time-sync-1.0.0.jar \
  --server.port=8062 \
  --time-sync.node-id=node2

# Node 3
java -jar target/time-sync-1.0.0.jar \
  --server.port=8063 \
  --time-sync.node-id=node3
```

### Verify clock synchronisation

```bash
# Check NTP sync status and current offsets on each node
curl http://localhost:8061/time/status
curl http://localhost:8062/time/status
curl http://localhost:8063/time/status

# Issue a hybrid timestamp from node1
curl http://localhost:8061/time/now

# Simulate a payment event arriving at node2 from node1 (logical time = 10)
curl -X POST http://localhost:8062/time/event \
  -H "Content-Type: application/json" \
  -d '{"receivedLogicalTime": 10}'
# Expected: logicalTime >= 11 (strictly greater than received)

# Force an immediate NTP re-sync (useful for testing)
# (No dedicated endpoint — restart triggers @PostConstruct,
#  or wait for the @Scheduled 60s cycle)
```

### Firewall note

The NTP sync sends a **UDP packet to port 123** on `pool.ntp.org`. Ensure outbound UDP port 123 is open on the server's firewall. If the environment blocks external UDP, point `time-sync.ntp-server` at an internal NTP server instead.

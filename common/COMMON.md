# Payment System — Common Module

This is the shared foundation library of the distributed payment processing system. It is a dependency-only Maven module — it has no `main` class, no REST endpoints, and does not run as a standalone service. Instead, it is compiled once and imported by every sibling module (`payment-gateway`, `replication`, `time-sync`, and any future modules) to ensure that core data structures and status contracts are defined in a single place and never duplicated.

---

## Table of Contents

- [Role in the System](#role-in-the-system)
- [Tech Stack](#tech-stack)
- [Module Contents](#module-contents)
  - [HybridTimestamp](#hybridtimestamp)
  - [PaymentTransaction](#paymenttransaction)
  - [PaymentStatus](#paymentstatus)
- [How These Types Flow Through the System](#how-these-types-flow-through-the-system)
- [Building & Using as a Dependency](#building--using-as-a-dependency)
- [Design Notes](#design-notes)

---

## Role in the System

```
                    ┌──────────────────────────────┐
                    │         common module         │
                    │                               │
                    │  HybridTimestamp              │
                    │  PaymentTransaction           │
                    │  PaymentStatus                │
                    └──────┬───────────┬────────────┘
                           │           │
           ┌───────────────┤           ├───────────────┐
           ▼               ▼           ▼               ▼
   ┌──────────────┐ ┌────────────┐ ┌───────────┐ ┌──────────────┐
   │payment-      │ │replication │ │time-sync  │ │  (future     │
   │gateway       │ │            │ │           │ │   modules)   │
   └──────────────┘ └────────────┘ └───────────┘ └──────────────┘
```

Every type defined here is the **single source of truth** for that concept across the entire cluster. When a `PaymentTransaction` is created in the `payment-gateway`, replicated by the `replication` module, and timestamped by `time-sync`, all three modules are working with the exact same class from this shared library — there is no translation layer or mapping between them.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17+ |
| Build Tool | Maven (child module of `distributed-payment-system`) |
| Serialization | Jackson (`jackson-databind`) |
| Utilities | Lombok (`@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`) |
| Packaging | JAR library — no Spring Boot, no embedded server |

The only external dependency is `jackson-databind`, inherited from the parent POM. This keeps the `common` module lightweight and free of framework coupling — any JVM module (Spring Boot, plain Java, test harnesses) can import it without pulling in unwanted transitive dependencies.

---

## Module Contents

### HybridTimestamp

**File:** `com.payment.common.model.HybridTimestamp`

The core timestamping primitive used by the entire system. It combines two independent time dimensions into a single comparable object:

```
HybridTimestamp {
    physicalTime  →  wall-clock milliseconds (optionally NTP-corrected)
    logicalTime   →  Lamport logical counter (causal ordering)
}
```

This hybrid design solves two distinct distributed systems problems simultaneously:
- **Physical drift** — different servers have slightly different wall clocks. `physicalTime` is corrected using the NTP offset provided by the `time-sync` module, anchoring all timestamps to a common reference.
- **Same-millisecond ambiguity** — when two events happen within the same millisecond, physical time cannot determine which came first. `logicalTime` (the Lamport counter) breaks the tie using causal ordering.

#### Factory Methods

| Method | Usage |
|---|---|
| `HybridTimestamp.now(logicalTime)` | Creates a timestamp using raw `System.currentTimeMillis()` — used when no NTP offset is available |
| `HybridTimestamp.nowWithOffset(ntpOffset, logicalTime)` | Creates an NTP-corrected timestamp — the standard call made by `time-sync`'s `ClockController` |

#### Comparison

`compareTo(HybridTimestamp other)` applies a two-level ordering:

```
1. Compare physicalTime — if different, the earlier wall-clock time wins
2. If physicalTime is equal, compare logicalTime — the lower Lamport counter wins
```

This ordering is consistent, transitive, and safe to use for sorting payment events or detecting causality violations across nodes.

---

### PaymentTransaction

**File:** `com.payment.common.model.PaymentTransaction`

The canonical wire format for a payment as it moves between services and across nodes. This is the object that the `replication` module fans out to peer nodes over HTTP, and the object the `payment-gateway` creates and stores locally.

```java
PaymentTransaction {
    String        transactionId      // UUID — globally unique payment identifier
    double        amount             // Payment value
    String        currency           // ISO currency code (e.g. "USD", "LKR")
    String        fromAccount        // Source account identifier
    String        toAccount          // Destination account identifier
    PaymentStatus status             // Current lifecycle state (see below)
    long          createdTimestamp   // Epoch milliseconds — set at creation time
    String        processedByNode    // Node ID that first accepted the transaction
}
```

**Key design decisions:**
- `transactionId` is the deduplication key. The `replication` module's `DuplicateChecker` uses `putIfAbsent` on this ID to guarantee exactly-once processing across concurrent requests.
- `processedByNode` is stamped at the primary node before replication, giving every committed transaction a clear ownership record — useful for debugging split-brain scenarios and audit trails.
- The class is a plain POJO with Lombok-generated builder, getters, setters, and a no-args constructor — making it directly serializable by Jackson over REST and deserializable on replica nodes without any additional configuration.

---

### PaymentStatus

**File:** `com.payment.common.model.PaymentStatus`

The shared enum that drives the entire payment lifecycle state machine across all modules. All status transitions happen against this single enum — there is no per-module copy.

| Status | Set By | Meaning |
|---|---|---|
| `PENDING` | `payment-gateway` | Transaction received, not yet processed or replicated |
| `PROCESSING` | `replication` / `payment-gateway` | Replication fan-out is in progress |
| `SUCCESS` | `replication` (QuorumManager) | Write quorum met — transaction durably committed |
| `FAILED` | `replication` / `payment-gateway` | Quorum not met, Stripe rejection, or timeout |
| `DUPLICATE` | `replication` (DuplicateChecker) | `transactionId` already seen — request rejected idempotently |

The `payment-gateway` module also defines a local copy of this enum (in `com.payment.model.PaymentStatus`) for its Stripe-specific status mapping. The canonical cross-module enum is this one in `com.payment.common.model`.

---

## How These Types Flow Through the System

```
1. Client submits payment to payment-gateway
        │
        ▼
2. payment-gateway creates PaymentTransaction
   (transactionId=UUID, status=PENDING, processedByNode=nodeX, createdTimestamp=now)
        │
        ▼
3. replication module receives PaymentTransaction via POST /payment
   DuplicateChecker.isDuplicate(transactionId) → atomic putIfAbsent
   QuorumManager sets status=PROCESSING, saves locally
        │
        ├──── fans out PaymentTransaction to replica nodes via POST /replicate
        │
        ▼
4. Quorum ACK count checked
   totalAcks >= writeQuorum  →  status = SUCCESS
   totalAcks <  writeQuorum  →  status = FAILED
        │
        ▼
5. time-sync module stamps each event with HybridTimestamp
   physicalTime = System.currentTimeMillis() + ntpOffset
   logicalTime  = Lamport counter after tick/receive
        │
        ▼
6. HybridTimestamp.compareTo() used to order events
   and detect causality across nodes
```

---

## Building & Using as a Dependency

### Build

The `common` module must be installed before any dependent module can compile:

```bash
# From the root of the distributed-payment-system project
mvn install -pl common -DskipTests

# Or build everything (common is built first due to dependency ordering)
mvn install -DskipTests
```

### Declare as a dependency in a sibling module

```xml
<dependency>
    <groupId>com.payment</groupId>
    <artifactId>common</artifactId>
    <!-- version inherited from parent POM -->
</dependency>
```

No version tag is needed — it is managed by the parent `distributed-payment-system` POM at version `1.0.0`.

---

## Design Notes

**Why a dedicated `common` module?**  
In a multi-module distributed system, duplicating shared types across services is a common source of subtle bugs — a field gets added in one service but not another, or two services interpret a status value differently. Centralising `PaymentTransaction`, `PaymentStatus`, and `HybridTimestamp` here eliminates that entire class of problem. Any change to these types is a single-point edit that is immediately visible to all modules at compile time.

**Why no Spring dependency?**  
The `common` module deliberately avoids Spring Boot. This keeps it usable from any context — including unit tests, plain Java utilities, and potential future non-Spring consumers — without forcing the full Spring autoconfiguration lifecycle onto those consumers.

**Why `double` for amount?**  
The current `PaymentTransaction.amount` uses `double`. For financial calculations in production this should be replaced with `BigDecimal` to avoid floating-point precision errors. The `payment-gateway` module already uses `BigDecimal` internally in its own `Payment` model — aligning `PaymentTransaction` to `BigDecimal` is a recommended follow-up.

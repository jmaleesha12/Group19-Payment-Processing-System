# Payment System — Payment Gateway Module

This is the primary entry-point microservice of the distributed payment processing system. It exposes a REST API for payment operations, integrates with **Stripe** for real-world payment processing, participates in a **quorum-based distributed consensus** cluster via ZooKeeper, and wraps all external calls with a **Resilience4j fault-tolerance** stack (circuit breaker, retry, timeout, rate limiter). It is designed to run as 3 identical nodes that collectively form a fault-tolerant payment cluster.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Tech Stack](#tech-stack)
- [Distributed Consensus Strategy](#distributed-consensus-strategy)
- [How Payment Processing Works](#how-payment-processing-works)
- [Fault Tolerance — Resilience4j Stack](#fault-tolerance--resilience4j-stack)
- [Stripe Integration](#stripe-integration)
- [API Endpoints](#api-endpoints)
- [Configuration](#configuration)
- [Running the Service](#running-the-service)
- [Known Limitations](#known-limitations)

---

## Architecture Overview

The payment-gateway is a child module of the parent `distributed-payment-system` Maven project. Three instances of this service run in parallel (node1, node2, node3), each aware of the others via `app.peers`. A ZooKeeper ensemble coordinates leader election and consensus decisions across nodes.

```
                          ┌───────────────────────────┐
                          │        Client / UI         │
                          │  (static/index.html)       │
                          └──────────┬────────────────┘
                                     │ HTTP
                    ┌────────────────┼────────────────┐
                    ▼                ▼                 ▼
             ┌───────────┐   ┌───────────┐   ┌───────────┐
             │  node1    │   │  node2    │   │  node3    │
             │  :8081    │   │  :8082    │   │  :8083    │
             └─────┬─────┘   └─────┬─────┘   └─────┬─────┘
                   │               │               │
                   └───────────────┼───────────────┘
                                   │
                          ┌────────▼───────┐
                          │   ZooKeeper    │  Leader election
                          │   :2181        │  Quorum consensus
                          └────────┬───────┘
                                   │
                          ┌────────▼───────┐
                          │  Stripe API    │  External payment processor
                          └────────────────┘

Per-node internal structure:
┌──────────────────────────────────────────────┐
│  PaymentController  (REST layer)             │
│       │                                      │
│  PaymentService  (business logic)            │
│       ├── Stripe SDK  (payment processing)   │
│       └── ConcurrentHashMap  (local store)   │
│                                              │
│  Config:                                     │
│   StripeConfig   (API key, webhook secret)   │
│   TimezoneConfig (Asia/Colombo, UTC+5:30)    │
│                                              │
│  Resilience4j:                               │
│   CircuitBreaker + Retry + TimeLimiter       │
│   + RateLimiter  (paymentService instance)   │
└──────────────────────────────────────────────┘
```

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17+ |
| Framework | Spring Boot |
| Build Tool | Maven (child module of `distributed-payment-system`) |
| Payment Processor | Stripe Java SDK |
| Distributed Coordination | Apache ZooKeeper + Apache Curator |
| Fault Tolerance | Resilience4j (CircuitBreaker, Retry, TimeLimiter, RateLimiter) |
| Consensus | ZooKeeper-based leader election + quorum (size = 2) |
| Storage | In-memory `ConcurrentHashMap` per node |
| Monitoring | Spring Boot Actuator (health, metrics, circuit breaker state) |
| Timezone | `Asia/Colombo` (UTC+5:30) — set globally on startup |
| Serialization | Jackson (ISO-8601 dates, non-null fields only) |
| Utilities | Lombok, Bean Validation (`@Valid`, `@NotNull`, `@Min`) |

---

## Distributed Consensus Strategy

The payment gateway participates in a 3-node cluster where consistency and leader election are managed via **Apache ZooKeeper**, coordinated through the **Apache Curator** framework.

### Leader Election

ZooKeeper handles leader election at the path configured by `consensus.leader-election-path=/election` within the `payment-system` namespace. Each node registers itself with Curator on startup. ZooKeeper's ephemeral sequential znode mechanism determines which node holds leadership at any given time. If the leader node crashes, ZooKeeper detects the session expiry (within `zookeeper.session-timeout-ms=30000`) and triggers re-election among the remaining nodes automatically.

### Quorum-Based Writes

Payment write operations require acknowledgment from a quorum of nodes before being committed. The quorum size is configured as `consensus.quorum-size=2` out of 3 total nodes. This means:

- The system can tolerate **1 node failure** and still commit payments successfully.
- A payment is only confirmed as `SUCCESS` once the quorum threshold is met.
- If quorum cannot be reached within `consensus.timeout-ms=5000`, the transaction is marked `FAILED`.

### Node Peer Awareness

Each node is pre-configured with its peer addresses via `app.peers`. This allows direct node-to-node communication for replication fan-out without routing through ZooKeeper for every operation. Each node profile (node1, node2, node3) has its own properties file specifying its port and peer list:

```
node1 (:8081) → peers: :8082, :8083
node2 (:8082) → peers: :8081, :8083
node3 (:8083) → peers: :8081, :8082
```

### Replication & Failover

Two background processes run continuously across nodes:

- **Replication** (`replication.sync-interval-ms=5000`): Syncs payment state to peer nodes every 5 seconds with up to 3 retries (`replication.max-retries=3`) and a per-attempt timeout of 3 seconds.
- **Failover / Heartbeat** (`failover.heartbeat-interval-ms=2000`): Nodes emit heartbeats every 2 seconds. If a node misses heartbeats beyond `failover.health-check-timeout-ms=5000`, it is considered unhealthy and the cluster adjusts accordingly.

---

## How Payment Processing Works

### Payment Status Lifecycle

```
PENDING ──► PROCESSING ──► SUCCESS
                       └──► FAILED
                       └──► DUPLICATE
```

| Status | Meaning |
|---|---|
| `PENDING` | PaymentIntent created with Stripe, not yet confirmed |
| `PROCESSING` | Being replicated across cluster nodes |
| `SUCCESS` | Confirmed by Stripe and committed to quorum |
| `FAILED` | Stripe rejection, replication failure, or quorum not met |
| `DUPLICATE` | Caught by deduplication — already processed |

### Flow 1 — Two-step Intent + Confirm (frontend use case)

```
Client ──► POST /api/payments/intent
              │
              ├─ Build PaymentIntentCreateParams (amount, currency, metadata)
              ├─ PaymentIntent.create()  →  Stripe API
              ├─ Store locally (status = PENDING)
              └─ Return clientSecret to frontend

Client collects card details using Stripe.js on the browser
              │
Client ──► POST /api/payments/confirm
              │
              ├─ PaymentIntent.retrieve(paymentIntentId)
              ├─ paymentIntent.confirm(paymentMethodId)
              ├─ mapStripeStatus() → internal PaymentStatus
              ├─ Update local store
              └─ Return PaymentResponse
```

### Flow 2 — One-step Direct Process (server-side / test use case)

```
Client ──► POST /api/payments/process
              │
              ├─ Resolve paymentMethodId (default: pm_card_visa)
              ├─ PaymentIntentCreateParams with setConfirm(true)
              ├─ PaymentIntent.create()  →  Stripe confirms immediately
              ├─ mapStripeStatus() → SUCCESS / PROCESSING / PENDING / FAILED
              ├─ Store result locally (ConcurrentHashMap)
              └─ Return PaymentResponse (with stripePaymentIntentId, status, nodeId)
```

### Stripe Status Mapping

| Stripe Status | Internal Status |
|---|---|
| `succeeded` | `SUCCESS` |
| `processing` | `PROCESSING` |
| `requires_payment_method` | `PENDING` |
| `requires_confirmation` | `PENDING` |
| `requires_action` | `PENDING` |
| `canceled` | `FAILED` |

---

## Fault Tolerance — Resilience4j Stack

All outbound payment service calls are protected by a four-layer Resilience4j configuration named `paymentService`.

### Circuit Breaker

Prevents cascading failures by stopping calls to a failing downstream (Stripe or peer nodes) when the error rate is too high.

| Setting | Value | Meaning |
|---|---|---|
| `sliding-window-type` | `COUNT_BASED` | Tracks last N calls |
| `sliding-window-size` | 10 | Evaluates the last 10 calls |
| `minimum-number-of-calls` | 5 | Needs at least 5 calls before evaluating |
| `failure-rate-threshold` | 50% | Opens circuit if ≥50% of calls fail |
| `wait-duration-in-open-state` | 10s | Stays open for 10 seconds before trying half-open |
| `permitted-number-of-calls-in-half-open-state` | 3 | Probes with 3 test calls when half-open |
| `automatic-transition-from-open-to-half-open` | enabled | No manual intervention needed |

**States:** `CLOSED` (normal) → `OPEN` (blocking) → `HALF_OPEN` (probing) → `CLOSED`

### Retry

Automatically retries transient failures (network timeouts, I/O errors) before propagating the error.

| Setting | Value |
|---|---|
| `max-attempts` | 3 |
| `wait-duration` | 500ms between attempts |
| `retry-exceptions` | `IOException`, `TimeoutException` |

### Time Limiter

Enforces a hard timeout on individual calls so a slow Stripe response cannot hold up threads indefinitely.

| Setting | Value |
|---|---|
| `timeout-duration` | 3 seconds |
| `cancel-running-future` | true — cancels the underlying async task |

### Rate Limiter

Caps the throughput of incoming payment requests to protect downstream Stripe API quotas.

| Setting | Value |
|---|---|
| `limit-for-period` | 100 requests |
| `limit-refresh-period` | 1 second |
| `timeout-duration` | 0s — reject immediately if limit exceeded |

---

## Stripe Integration

The `StripeConfig` class initialises the Stripe Java SDK globally on startup via `@PostConstruct` by setting `Stripe.apiKey`. The service runs in **Stripe Test Mode** — no real money moves.

### Test Payment Method Tokens

The `/api/payments/test` endpoint maps simple card aliases to Stripe's built-in test tokens:

| Alias | Stripe Token | Behaviour |
|---|---|---|
| `visa` (default) | `pm_card_visa` | Always succeeds |
| `mastercard` | `pm_card_mastercard` | Always succeeds |
| `amex` | `pm_card_amex` | Always succeeds |
| `declined` | `pm_card_visa_chargeDeclined` | Always declines |

### Webhook Support

The `stripe.webhook.secret` property is pre-wired for Stripe webhook verification but the webhook handler is not yet implemented in this module. Set `STRIPE_WEBHOOK_SECRET` as an environment variable to enable it when a handler is added.

---

## API Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/health` | Service health check |
| `GET` | `/api/payments/health` | Payment service health with timestamp |
| `POST` | `/api/payments/intent` | Create a Stripe PaymentIntent (returns `clientSecret`) |
| `POST` | `/api/payments/process` | Create and auto-confirm a payment in one step |
| `POST` | `/api/payments/test` | Quick test payment with card type alias |
| `POST` | `/api/payments/confirm` | Confirm an existing PaymentIntent with a PaymentMethod |
| `GET` | `/api/payments/{id}` | Retrieve a payment by internal ID |
| `GET` | `/api/payments` | List all payments stored on this node |

### Example — One-step payment

```http
POST /api/payments/process
Content-Type: application/json

{
  "amount": 2500,
  "currency": "usd",
  "description": "Order #1042",
  "paymentMethodId": "pm_card_visa"
}
```

**Response (200 OK):**
```json
{
  "id": "a1b2c3d4-...",
  "stripePaymentIntentId": "pi_3ABC...",
  "amount": 2500,
  "currency": "usd",
  "description": "Order #1042",
  "status": "SUCCESS",
  "clientSecret": "pi_3ABC..._secret_...",
  "createdAt": "2026-03-27 15:30:00",
  "nodeId": "node1"
}
```

### Example — Quick test (query params only)

```http
POST /api/payments/test?amount=5000&currency=usd&card=declined
```

**Response:**
```json
{
  "status": "FAILED",
  "errorMessage": "Your card was declined.",
  "nodeId": "node2"
}
```

### Example — Two-step intent + confirm

```http
# Step 1
POST /api/payments/intent
{ "amount": 1000, "currency": "usd" }
→ { "clientSecret": "pi_xxx_secret_yyy", ... }

# Step 2 (after Stripe.js collects card on frontend)
POST /api/payments/confirm
{ "paymentIntentId": "pi_xxx", "paymentMethodId": "pm_yyy" }
→ { "status": "SUCCESS", ... }
```

---

## Configuration

### Core settings (`application.properties`)

```properties
# Node identity
app.node.id=node1
app.node.host=localhost
app.peers=http://localhost:8082,http://localhost:8083

# ZooKeeper
zookeeper.connect-string=localhost:2181
zookeeper.session-timeout-ms=30000
zookeeper.namespace=payment-system

# Consensus
consensus.quorum-size=2
consensus.timeout-ms=5000
consensus.leader-election-path=/election

# Stripe
stripe.api.key=sk_test_...    # Replace with your key from dashboard.stripe.com
stripe.webhook.secret=${STRIPE_WEBHOOK_SECRET:}

# Replication
replication.enabled=true
replication.sync-interval-ms=5000
replication.max-retries=3

# Failover
failover.enabled=true
failover.heartbeat-interval-ms=2000
```

### Per-node overrides

| File | Node | Port |
|---|---|---|
| `application-node1.properties` | node1 | 8081 |
| `application-node2.properties` | node2 | 8082 |
| `application-node3.properties` | node3 | 8083 |

Activate a profile with `--spring.profiles.active=node1`.

---

## Running the Service

### Prerequisites

- Java 17+
- Maven 3.8+
- ZooKeeper instance running on `localhost:2181`
- A Stripe test API key from [dashboard.stripe.com/test/apikeys](https://dashboard.stripe.com/test/apikeys)
- Parent POM (`distributed-payment-system`) installed locally

### Build

```bash
# From the root of the distributed-payment-system project
mvn install -DskipTests

# Or build only this module
cd payment-gateway
mvn package -DskipTests
```

### Run ZooKeeper (Docker — quickest)

```bash
docker run -d --name zookeeper -p 2181:2181 zookeeper:3.8
```

### Run a single node

```bash
java -jar target/payment-gateway-1.0.0.jar \
  --spring.profiles.active=node1 \
  --stripe.api.key=sk_test_YOUR_KEY_HERE
```

### Run a 3-node cluster

```bash
# Terminal 1
java -jar target/payment-gateway-1.0.0.jar \
  --spring.profiles.active=node1 \
  --stripe.api.key=sk_test_YOUR_KEY_HERE

# Terminal 2
java -jar target/payment-gateway-1.0.0.jar \
  --spring.profiles.active=node2 \
  --stripe.api.key=sk_test_YOUR_KEY_HERE

# Terminal 3
java -jar target/payment-gateway-1.0.0.jar \
  --spring.profiles.active=node3 \
  --stripe.api.key=sk_test_YOUR_KEY_HERE
```

### Health Check

```bash
curl http://localhost:8081/api/health
# {"status":"UP","service":"Payment Processing System"}

curl http://localhost:8081/actuator/health
# Shows circuit breaker state, disk, and full health tree
```

### Run a test payment

```bash
curl -X POST "http://localhost:8081/api/payments/test?amount=1000&currency=usd&card=visa"
```

---

## Known Limitations

- **No persistent database:** All payment records are in-memory `ConcurrentHashMap` per node. Data is lost on restart. A relational or distributed database is needed for production.
- **Stripe webhook handler not implemented:** `stripe.webhook.secret` is configured but there is no endpoint to receive or verify Stripe webhook events.
- **`@CrossOrigin(origins = "*")`:** CORS is fully open for testing. This must be restricted to specific origins before any production deployment.
- **No authentication/authorisation:** The REST API has no token or session protection. API gateway-level auth (e.g. JWT, OAuth2) must be added before exposing to clients.
- **Replication and heartbeat are configuration-driven but not wired in this module:** The `replication.*` and `failover.*` properties are declared and ready, but their corresponding scheduled beans live in separate sibling modules (`replication`, `time-sync`). This module alone does not run those background tasks.
- **In-memory Lamport / quorum state:** The ZooKeeper integration is configured but the Curator client bean and leader-election logic are not visible in this module's source — they are expected to be provided by the `common` module or a future `consensus` module.

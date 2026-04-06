# Group 19 — Distributed Payment Processing System
 
A distributed payment processing system built for an e-commerce platform supporting concurrent transactions. Maintains consistency during node failures and network delays while synchronizing payment history across servers. Integrates with the **Stripe** payment gateway (sandbox/test mode) and demonstrates fault tolerance, recovery mechanisms, and high availability in distributed environments.
 
---
 
## Team Members & Responsibilities
 
| Registration No. | Name | Email | Responsibility |
|---|---|---|---|
| IT24101508 | Maleesha P.K.B.J | it24101508@my.sliit.lk | Consensus & Agreement Algorithms (`consensus` module) |
| IT24101846 | Nanayakkara K.A.D.M.D | it24101846@my.sliit.lk | Fault Tolerance (`fault-tolerance` module) |
| IT24101499 | Herath H.R.M.D.C.B | it24101499@my.sliit.lk | Data Replication (`replication` module) |
| IT24101643 | Ranasingha J.A.D.S.L | it24101643@my.sliit.lk | Time Synchronization (`time-sync` module) |
 
---
 
## Project Structure
 
```
distributed-payment-system/
├── common/            # Shared models — PaymentTransaction, HybridTimestamp, PaymentStatus
├── consensus/         # Leader election & quorum via Apache ZooKeeper (IT24101508)
├── fault-tolerance/   # Circuit breaker, retry, rate limiter via Resilience4j (IT24101846)
├── payment-gateway/   # REST API & Stripe integration — runs as 3 nodes (IT24101508)
├── replication/       # Quorum-based data replication across nodes (IT24101499)
├── time-sync/         # NTP physical sync + Lamport logical clock (IT24101643)
└── pom.xml            # Parent POM
```
 
---
 
## Prerequisites
 
Before running the system, make sure you have the following installed and ready:
 
- **Java 21** or higher
- **Maven 3.8+**
- **Apache ZooKeeper 3.8+** — required for the consensus module
- **Stripe Test API Key** — get yours free at [dashboard.stripe.com/test/apikeys](https://dashboard.stripe.com/test/apikeys)
- **Docker** *(optional but recommended)* — for running ZooKeeper quickly
 
---
 
## Running the System
 
### Step 1 — Start ZooKeeper
 
The consensus module requires a running ZooKeeper instance on port `2181`.
 
**Using Docker (recommended):**
```bash
docker run -d --name zookeeper -p 2181:2181 zookeeper:3.8
```
 
**Using a local ZooKeeper installation:**
```bash
zkServer.sh start        # macOS / Linux
zkServer.cmd start       # Windows
```
 
---
 
### Step 2 — Build the Project
 
From the root of the project, build all modules:
 
```bash
mvn clean install -DskipTests
```
 
---
 
### Step 3 — Run Individual Modules
 
Each module runs as an independent Spring Boot service. Open a separate terminal for each.
 
#### Payment Gateway (3 nodes — run all three for full distributed setup)
 
```bash
# Node 1
cd payment-gateway
mvn spring-boot:run -Dspring-boot.run.profiles=node1 -Dspring-boot.run.arguments="--stripe.api.key=sk_test_YOUR_KEY_HERE"
 
# Node 2 (new terminal)
cd payment-gateway
mvn spring-boot:run -Dspring-boot.run.profiles=node2 -Dspring-boot.run.arguments="--stripe.api.key=sk_test_YOUR_KEY_HERE"
 
# Node 3 (new terminal)
cd payment-gateway
mvn spring-boot:run -Dspring-boot.run.profiles=node3 -Dspring-boot.run.arguments="--stripe.api.key=sk_test_YOUR_KEY_HERE"
```
 
| Node | Port |
|---|---|
| node1 | 8081 |
| node2 | 8082 |
| node3 | 8083 |
 
#### Replication Module
 
```bash
cd replication
mvn spring-boot:run
```
 
Runs on port **8091**. Handles quorum-based replication across nodes (W=2 of N=3).
 
#### Time Sync Module
 
```bash
cd time-sync
mvn spring-boot:run
```
 
Runs on port **8061**. Syncs with `pool.ntp.org` and maintains the Lamport logical clock.
 
#### Consensus Module
 
```bash
cd consensus
mvn spring-boot:run
```
 
Connects to ZooKeeper on `localhost:2181`. Manages leader election and quorum decisions.
 
#### Fault Tolerance Module
 
```bash
cd fault-tolerance
mvn spring-boot:run
```
 
Provides circuit breaker, retry, timeout, and rate limiter protection for all outbound calls.
 
---
 
### Step 4 — Run the Full System
 
Start everything in the following order to ensure dependencies are available before dependent services connect:
 
```bash
# Terminal 1 — ZooKeeper (skip if using Docker)
zkServer.sh start
 
# Terminal 2 — Time Sync
cd time-sync && mvn spring-boot:run
 
# Terminal 3 — Consensus
cd consensus && mvn spring-boot:run
 
# Terminal 4 — Replication
cd replication && mvn spring-boot:run
 
# Terminal 5 — Fault Tolerance
cd fault-tolerance && mvn spring-boot:run
 
# Terminal 6 — Payment Gateway Node 1
cd payment-gateway && mvn spring-boot:run -Dspring-boot.run.profiles=node1 -Dspring-boot.run.arguments="--stripe.api.key=sk_test_YOUR_KEY_HERE"
 
# Terminal 7 — Payment Gateway Node 2
cd payment-gateway && mvn spring-boot:run -Dspring-boot.run.profiles=node2 -Dspring-boot.run.arguments="--stripe.api.key=sk_test_YOUR_KEY_HERE"
 
# Terminal 8 — Payment Gateway Node 3
cd payment-gateway && mvn spring-boot:run -Dspring-boot.run.profiles=node3 -Dspring-boot.run.arguments="--stripe.api.key=sk_test_YOUR_KEY_HERE"
```
 
---
 
### Step 5 — Verify Everything is Running
 
```bash
# Payment Gateway nodes
curl http://localhost:8081/api/health
curl http://localhost:8082/api/health
curl http://localhost:8083/api/health
 
# Replication module
curl http://localhost:8091/health
 
# Time Sync module
curl http://localhost:8061/health
curl http://localhost:8061/time/status
 
# Actuator (circuit breaker state, metrics)
curl http://localhost:8081/actuator/health
```
 
All healthy nodes return: `{"status":"UP", ...}`
 
---
 
## Running Tests
 
```bash
# Run all tests across all modules
mvn test
 
# Run tests for a specific module
cd replication && mvn test
cd time-sync && mvn test
cd payment-gateway && mvn test
```
 
---
 
## Prototype — Demo Guide
 
Once the full system is running, use the steps below to demonstrate each member's component in action.
 
---
 
### Consensus & Payment Gateway — IT24101508 (Maleesha)
 
Submit a successful test payment through node1:
 
```bash
curl -X POST "http://localhost:8081/api/payments/test?amount=1500&currency=usd&card=visa"
```
 
Submit a declined card to observe failure handling:
 
```bash
curl -X POST "http://localhost:8081/api/payments/test?amount=1500&currency=usd&card=declined"
```
 
Submit a full payment with all details:
 
```bash
curl -X POST http://localhost:8081/api/payments/process \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 5000,
    "currency": "usd",
    "description": "Order #2001",
    "paymentMethodId": "pm_card_visa"
  }'
```
 
List all payments on node1:
 
```bash
curl http://localhost:8081/api/payments
```
 
**Expected result:** Transaction comes back with `"status": "SUCCESS"` and a `stripePaymentIntentId` from Stripe's test environment.
 
---
 
### Quorum Replication — IT24101499 (Herath)
 
Submit a payment directly to the replication module:
 
```bash
curl -X POST http://localhost:8091/payment \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "txn-demo-001",
    "amount": 250.00,
    "currency": "USD",
    "fromAccount": "acc-001",
    "toAccount": "acc-002"
  }'
```
 
Check replication status and transaction count across nodes:
 
```bash
curl http://localhost:8091/replication/status
curl http://localhost:8091/transactions
```
 
**Simulate 1 node failure — resilience demo:**
 
1. Stop node3 by pressing `Ctrl+C` on Terminal 8
2. Resubmit a payment with a new `transactionId`
3. The system still commits — quorum (W=2) is still met with node1 and node2
 
```bash
curl -X POST http://localhost:8091/payment \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "txn-demo-002",
    "amount": 100.00,
    "currency": "USD",
    "fromAccount": "acc-001",
    "toAccount": "acc-003"
  }'
```
 
**Expected result:** `"status": "SUCCESS"` even with one node down.
 
**Test duplicate detection** — send the same `transactionId` twice:
 
```bash
# First call — succeeds
curl -X POST http://localhost:8091/payment \
  -H "Content-Type: application/json" \
  -d '{"transactionId": "txn-dup-test", "amount": 50.00, "currency": "USD"}'
 
# Second call — rejected
curl -X POST http://localhost:8091/payment \
  -H "Content-Type: application/json" \
  -d '{"transactionId": "txn-dup-test", "amount": 50.00, "currency": "USD"}'
```
 
**Expected result:** Second call returns HTTP `409` with `"status": "DUPLICATE"`.
 
---
 
### Time Synchronization — IT24101643 (Ranasingha)
 
Get the current hybrid timestamp (NTP-corrected physical time + Lamport counter):
 
```bash
curl http://localhost:8061/time/now
```
 
**Expected response:**
```json
{
  "physicalTime": 1743120000123,
  "logicalTime": 1
}
```
 
Check full NTP sync status:
 
```bash
curl http://localhost:8061/time/status
```
 
**Expected response:**
```json
{
  "nodeId": "node1",
  "ntpOffset": -8,
  "syncSuccessful": true,
  "lamportTime": 3,
  "correctedTime": 1743120000115
}
```
 
Simulate receiving a message from a node with a higher logical clock:
 
```bash
curl -X POST http://localhost:8061/time/event \
  -H "Content-Type: application/json" \
  -d '{"receivedLogicalTime": 99}'
```
 
**Expected result:** `"logicalTime": 100` — because `max(local, 99) + 1 = 100`. This is the Lamport receive rule: the clock always jumps forward to stay causally consistent.
 
Call `/time/now` multiple times and observe `logicalTime` incrementing monotonically — it never goes backward.
 
---
 
### Fault Tolerance — IT24101846 (Nanayakkara)
 
Check live circuit breaker and health state:
 
```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8081/actuator/circuitbreakers
```
 
View real-time metrics (call counts, failure rate, latency):
 
```bash
curl http://localhost:8081/actuator/metrics
```
 
**Demonstrate the rate limiter** — send 110 requests in rapid succession (limit is 100/second):
 
```bash
for i in {1..110}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    "http://localhost:8081/api/payments/test?amount=100&currency=usd&card=visa"
done
```
 
**Expected result:** First 100 requests return `200`. Requests 101–110 return `429 Too Many Requests`.
 
**Demonstrate the circuit breaker** — set an invalid Stripe API key in `application.properties`, restart node1, then send 10 requests:
 
```bash
for i in {1..10}; do
  curl -X POST "http://localhost:8081/api/payments/test?amount=100&currency=usd&card=visa"
done
```
 
After 5+ calls with a ≥50% failure rate, the circuit **opens** — subsequent calls fast-fail instantly without reaching Stripe. After 10 seconds it moves to **half-open** (3 probe calls allowed), then **closes** once probes succeed.
 
---
 
## Service Port Reference
 
| Module | Port | Owner |
|---|---|---|
| payment-gateway node1 | 8081 | IT24101508 — Maleesha |
| payment-gateway node2 | 8082 | IT24101508 — Maleesha |
| payment-gateway node3 | 8083 | IT24101508 — Maleesha |
| replication | 8091 | IT24101499 — Herath |
| time-sync | 8061 | IT24101643 — Ranasingha |
| consensus / ZooKeeper | 2181 | IT24101508 — Maleesha |
| fault-tolerance (Actuator) | 8081/actuator | IT24101846 — Nanayakkara |
 
---
 
## License
 
This project is developed for academic purposes at SLIIT — Sri Lanka Institute of Information Technology.

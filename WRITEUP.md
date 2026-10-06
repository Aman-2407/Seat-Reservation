1. The Atomic Decision & Concurrency Control
   The core challenge of this system is preventing double-booking under heavy concurrent load (the "stampede"). To guarantee race-free reservations, the system relies on the ACID properties of the MySQL database rather than application-level synchronization.

Atomic Mechanism: Seat reservations are enforced via database constraints (Unique Index on show_id and seat_identifier) combined with atomic UPDATE statements or pessimistic row locks (SELECT ... FOR UPDATE). When hundreds of requests attempt to claim the same seat simultaneously, the database serializes these operations. Only the first transaction succeeds; the rest fail with a constraint violation or lock timeout, safely translating into a 409 Seat Taken response.

Multi-Seat Deadlock Prevention: When a single request attempts to reserve multiple seats, acquiring locks in an arbitrary order can lead to deadlocks (e.g., Request A locks Seat 1 then 2; Request B locks Seat 2 then 1). To avoid this, the application strictly sorts the seat identifiers lexicographically before initiating the database update. This ensures all concurrent transactions acquire row locks in the exact same sequence, mathematically eliminating the possibility of circular wait deadlocks.

2. Idempotency Guarantee
   Network instability means clients will frequently retry requests. The system must ensure that a retried request does not result in a duplicate reservation or charge.

Key Storage & Exactly-Once Execution: The client-provided Idempotency-Key (or Request ID) is stored in a dedicated database column with a UNIQUE constraint. When a reservation transaction begins, the system attempts to insert or log this key. If the key already exists, MySQL throws a DuplicateKeyException, immediately halting the transaction and guaranteeing exactly-once execution.

Same-Key-Different-Body Handling: If a duplicate key is detected, the system retrieves the original request payload hash (or parameters) associated with that key. If the new request body differs from the original (e.g., the client retried with the same key but different seat selections), the system rejects it with a 409 Idempotency Mismatch to prevent malicious or buggy client behavior. If they match, it returns the cached 200 OK response of the original successful transaction.

3. Holds & Expiry
   To prevent users from indefinitely locking up inventory, the system implements a temporary hold mechanism.

When a user selects a seat, its status transitions to HELD and an expires_at timestamp is written to the row.

If the user completes the transaction before the timestamp, the status updates to CONFIRMED.

If the timestamp passes, the seat becomes available again. This is typically enforced via lazy evaluation (where subsequent read/write queries treat expired holds as AVAILABLE) or a background sweeper job that periodically resets expired rows to keep the database state clean.

4. Consistency vs. Availability Under a Partition
   In the context of the CAP theorem, a ticketing system must fundamentally prioritize Consistency (CP) over Availability (AP).

If a network partition occurs (e.g., the application servers lose communication with the primary MySQL database), the system will fail closed. It will return 5xx server errors or timeouts rather than accepting reservations locally and attempting to merge them later. Eventual consistency is unacceptable in this domain because it leads to over-selling physical inventory, resulting in severe customer trust issues and manual conflict resolution at the venue.

5. Observability: 2 AM Paging Scenarios
   The system uses RequestLogFilter to inject a requestId into the MDC, generating structured ECS JSON logs that allow tracing a single request from the ingress point through the database layer. Prometheus metrics expose real-time application health.

I would configure PagerDuty alerts to wake me up at 2 AM for the following critical thresholds:

High 5xx Error Rate: A spike in internal server errors indicating application crashes, unhandled exceptions, or database connectivity loss.

Database Connection Pool Exhaustion: When HikariCP reports zero active connections available or frequent ConnectionTimeoutExceptions. This indicates the application is deadlocking or the database cannot process transactions fast enough, leading to cascading failures.

Readiness Probe Failure: If the /actuator/health/readiness endpoint drops to DOWN, meaning the load balancer is about to pull the instances out of rotation and halt all traffic.

6. AI Usage (Directed vs. Decided)
   I utilized an AI assistant primarily as a directed pair-programmer and operational guide.

Directed: I provided the architectural constraints (Spring Boot, Aiven MySQL, Render Docker deployment) and used the AI to generate the boilerplate RequestLogFilter, format the application.properties, and troubleshoot deployment connection strings (sslMode=REQUIRED). I also used it to debug the Burst.java load-testing script, directing it to help me adjust HTTP client timeouts and implement thread cooldowns to accommodate free-tier infrastructure limits.

Decided: The AI proposed the structured JSON logging library (ecs) and the exact syntax for the Prometheus metric increments, which I accepted to fulfill the observability requirements.

7. What I Would Do Next
   If moving this to a production enterprise environment, I would implement the following:

Redis for Holds and Caching: Move the temporary seat holds and idempotency keys to a Redis cluster with automatic TTL (Time-To-Live) expiry, significantly reducing the write-load and lock contention on the primary MySQL database.

Read Replicas: Route all read-only traffic (e.g., users viewing the seat map) to MySQL read replicas, reserving the primary database node exclusively for write operations (reservations).

Asynchronous Checkout: Decouple the payment/confirmation phase using a message broker (like Kafka or RabbitMQ) to handle post-reservation processing (email generation, ticketing) asynchronously, keeping the synchronous HTTP request cycle as fast as possible.
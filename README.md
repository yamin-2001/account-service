# account-service

> The customer bank-account service of the banking platform: it owns the `accounts` table (number, owner, type, currency, lifecycle status) and opens accounts asynchronously through the core banking system. HTTP port **8081** (Actuator/metrics on **18081**).

- [What It Does](#what-it-does)
- [Architecture At A Glance](#architecture-at-a-glance)
- [HTTP API](#http-api)
- [Error Response Shape](#error-response-shape)
- [Database](#database)
- [Kafka Topics](#kafka-topics)
- [Configuration](#configuration)
- [Behaviour Rules](#behaviour-rules)
- [Running It](#running-it)
- [API Quick Reference (curl examples)](#api-quick-reference-curl-examples)
- [Configuration Reference (quick table)](#configuration-reference-quick-table)
- [Troubleshooting](#troubleshooting)
- [Notes and Known Limitations](#notes-and-known-limitations)

## What It Does

`account-service` owns the customer **bank accounts** — the `accounts` table: the account number, the owning user id, the account type (`SAVINGS`/`CURRENT`), the currency, the lifecycle status (`PENDING`/`ACTIVE`/`BLOCKED`/`OPENING_FAILED`) and the failure reason. It also owns `outbox_events` (the transactional outbox) and `processed_events` (the idempotency ledger).

It exposes the public `/api/accounts` endpoints — create, list mine, get one — that the API gateway (`api-gateway`, port 8080) routes from `/api/accounts/**`, and the internal `/internal/accounts` endpoints used by other services to look up account metadata. The internal paths are never routed through the gateway: the gateway has a `blocked-internal` route (`Path=/internal/**` → `uri: no://op`) and its `JwtAuthenticationFilter` answers **404** for any `/internal` path.

The user identity comes from the **`X-User-Id` request header**, which the gateway's `JwtAuthenticationFilter` sets from the validated JWT (`sub` claim). This service has no JWT and no Spring Security dependency at all — it does not even have `spring-boot-starter-security` on the classpath.

Account opening is **asynchronous and event-driven**. `POST /api/accounts` creates the row with status `PENDING` and publishes a `CreateLedgerAccount` command to the `cbs.commands` topic in the same database transaction. The `core-banking-service` (port 8084) creates the real ledger account and answers on `cbs.events` with `LedgerAccountCreated` or `LedgerAccountCreationFailed`. This service then flips the account to `ACTIVE` (or `OPENING_FAILED`) and publishes `AccountOpened` on `account.events`.

Balances are **not** stored here — the `accounts` table has no balance column. They are read from the core banking service over HTTP (`GET /internal/cbs/balances`).

The service also answers `ValidateTransfer` and `ValidateCashAccount` commands on the `account.commands` topic — used by the fund-transfer saga in `fund-transfer-service` (port 8082) — with `TransferValidated`, `TransferRejected`, `CashAccountValidated` and `CashAccountRejected` events on `account.events`.

It does **not** own the customer profile (`user-service`, port 8083), the ledger or the balances (`core-banking-service`, port 8084), the transfer orchestration (`fund-transfer-service`, port 8082), or the messages (`notification-service`, port 8087).

## Architecture At A Glance

```
   client ──GET/POST /api/accounts──▶ api-gateway (8080) ──▶ account-service (8081)
                                        (sets X-User-Id)              │
                                                                    ▼
                                                            accounts (PENDING)
                                                                    │
                                              outbox_events (transactional)
                                                                    │
                                                            outbox relay (1 s poll)
                                                                    ▼
                                              Kafka topic  cbs.commands
                                              event: CreateLedgerAccount
                                                                    │
                                                                    ▼
                                              core-banking-service (8084)
                                                                    │
                                              Kafka topic  cbs.events
                                              event: LedgerAccountCreated / LedgerAccountCreationFailed
                                                                    │
                                                                    ▼
                                     accounts (ACTIVE / OPENING_FAILED)
                                     + account.events: AccountOpened

   fund-transfer-service (8082) ──▶ account.commands
                                     ValidateTransfer / ValidateCashAccount
                                            │
                                            ▼
                                     account.events
                                     TransferValidated / TransferRejected
                                     CashAccountValidated / CashAccountRejected
```

| Depends on | Depended on by |
|---|---|
| PostgreSQL `account_db` (127.0.0.1:5433) | `api-gateway` (`/api/accounts/**`) |
| Kafka (localhost:9092) — producer **and** consumer | `core-banking-service` (`cbs.commands` consumer) |
| Eureka (`discovery-service`, port 8761) | `fund-transfer-service` (`account.commands` producer) |
| `core-banking-service` (port 8084) — balances over HTTP | `notification-service`, `charge-fee-service` (internal account lookups) |

## HTTP API

The service listens on port **8081**. The public endpoints require the `X-User-Id` header; the internal endpoints do not.

### `GET /api/accounts`

Lists the caller's own accounts, oldest first.

Request header `X-User-Id` — required, type `Long`.

Response body — an array of `com.banking.account.dto.AccountResponse`:

| Field | Type | Value |
|---|---|---|
| `accountNumber` | `String` | e.g. `ACC-12345678` |
| `accountType` | `String` | `account.getAccountType().name()` → `SAVINGS` / `CURRENT` |
| `balance` | `BigDecimal` | `0.00` for `PENDING` accounts; the CBS balance for `ACTIVE`/`BLOCKED`; scale 2 |
| `currency` | `String` | e.g. `USD` |
| `status` | `String` | `account.getStatus().name()` → `PENDING` / `ACTIVE` / `BLOCKED` / `OPENING_FAILED` |
| `reason` | `String` | `account.getFailureReason()` — nullable; only set for `OPENING_FAILED` |

Accounts in `OPENING_FAILED` are **not** listed.

Status codes:

| Code | When |
|---|---|
| **200 OK** | The list, possibly empty (`[]` — an empty result is 200, not 404) |
| **401 Unauthorized** | The `X-User-Id` header is missing — message `Missing user identity` |
| **400 Bad Request** | The `X-User-Id` header is not a number — message `Invalid value for X-User-Id` |
| **503 Service Unavailable** | The core banking service could not be reached — message `Balances are temporarily unavailable, please try again` |
| **503 Service Unavailable** | An `ACTIVE`/`BLOCKED` account has no ledger balance — message `Balance temporarily unavailable, please try again` |

### `GET /api/accounts/{accountNumber}`

Returns one of the caller's own accounts.

Path variable `accountNumber` — type `String`. Request header `X-User-Id` — required, type `Long`.

The lookup is `findByAccountNumberAndUserId(accountNumber.trim(), userId)`: the account must belong to the caller, otherwise it is reported as "not found". The response body is `AccountResponse` — the same fields as in the list above.

Status codes:

| Code | When |
|---|---|
| **200 OK** | The account exists and belongs to the caller |
| **404 Not Found** | The account does not exist or does not belong to the caller — message `Account not found: <accountNumber>` |
| **401 Unauthorized** | The `X-User-Id` header is missing — message `Missing user identity` |
| **400 Bad Request** | The `X-User-Id` header is not a number — message `Invalid value for X-User-Id` |
| **503 Service Unavailable** | The core banking service could not be reached — message `Balances are temporarily unavailable, please try again` |
| **503 Service Unavailable** | An `ACTIVE`/`BLOCKED` account has no ledger balance — message `Balance temporarily unavailable, please try again` |

### `POST /api/accounts`

Opens a new account. The account row is created immediately with status `PENDING`; the ledger account is opened asynchronously by the core banking service.

Request header `X-User-Id` — required, type `Long`.

Request body — `com.banking.account.dto.CreateAccountRequest`:

| Field | Type | Required | Validation | Constraint message |
|---|---|---|---|---|
| `accountType` | `AccountType` (enum) | yes | `@NotNull` | `accountType is required` |

`com.banking.account.entity.AccountType` → `SAVINGS`, `CURRENT`.

Response body — `AccountResponse`:

| Field | Type | Value on creation |
|---|---|---|
| `accountNumber` | `String` | The generated number, e.g. `ACC-12345678` |
| `accountType` | `String` | `request.accountType().name()` |
| `balance` | `BigDecimal` | `0.00` (`BigDecimal.ZERO.setScale(2)`) |
| `currency` | `String` | `app.account.default-currency` → `USD` |
| `status` | `String` | `PENDING` |
| `reason` | `String` | `null` |

Status codes:

| Code | When |
|---|---|
| **202 Accepted** | The account row was created and the `CreateLedgerAccount` command was published. *"202 Accepted: the account is created, but CBS opens its ledger account asynchronously."* |
| **409 Conflict** | The user already has 5 accounts — message `You can have at most 5 accounts` |
| **503 Service Unavailable** | Five account-number generations all collided — message `Could not create the account, please try again` |
| **401 Unauthorized** | The `X-User-Id` header is missing — message `Missing user identity` |
| **400 Bad Request** | The `X-User-Id` header is not a number — message `Invalid value for X-User-Id` |
| **400 Bad Request** | `accountType` is missing — message `accountType: accountType is required` |
| **400 Bad Request** | The body is not readable JSON, or `accountType` is not `SAVINGS` or `CURRENT` — message `Malformed request body or invalid accountType (SAVINGS or CURRENT)` |

### `GET /internal/accounts/{accountNumber}`

Returns the metadata of one account, including its owner. Used by other services; never routed through the gateway.

Path variable `accountNumber` — type `String`.

Response body — `com.banking.account.dto.AccountDetailsResponse`:

| Field | Type | Value |
|---|---|---|
| `accountNumber` | `String` | |
| `userId` | `Long` | The owning user |
| `accountType` | `String` | `SAVINGS` / `CURRENT` |
| `currency` | `String` | e.g. `USD` |
| `status` | `String` | `PENDING` / `ACTIVE` / `BLOCKED` / `OPENING_FAILED` |

Status codes:

| Code | When |
|---|---|
| **200 OK** | The account exists |
| **404 Not Found** | The account does not exist — message `Account not found: <accountNumber>` |

### `GET /internal/accounts?accountNumbers=A,B`

Returns the metadata of several accounts at once. The query parameter `accountNumbers` is a `List<String>`; Spring binds repeated parameters and comma-separated values.

Unknown account numbers are **silently omitted** — only rows found in `accounts` are returned. The service-side `getDetails(List<String>)` returns `List.of()` for a null or empty list.

Response body — an array of `AccountDetailsResponse` (the same fields as above).

Status codes:

| Code | When |
|---|---|
| **200 OK** | The list, possibly empty (`[]`) |
| **500 Internal Server Error** | The `accountNumbers` query parameter is missing entirely — it is a required `@RequestParam`, and `MissingServletRequestParameterException` is not handled specially, so it falls into the catch-all (message `Something went wrong`) |

### Outbound HTTP call

`com.banking.account.client.CbsClientImpl` calls the core banking service:

```
GET {app.cbs.base-url}/internal/cbs/balances?accountNumbers=ACC-1,ACC-2
```

`app.cbs.base-url` is `http://core-banking-service`, resolved through Eureka by the load balancer. The client has a connect timeout of 3000 ms and a read timeout of 10000 ms (`config/RestClientConfig.java`). The CBS side returns `List<BalanceResponse>` where `BalanceResponse(String accountNumber, BigDecimal balance, String currency)` — the same shape as `com.banking.account.client.CbsBalance`.

### Error Response Shape

Every error is produced by `com.banking.account.exception.GlobalExceptionHandler` (`@RestControllerAdvice`) as `com.banking.account.exception.ErrorResponse`:

| Field | Type | Meaning |
|---|---|---|
| `timestamp` | `LocalDateTime` | `LocalDateTime.now()` when the error was built |
| `status` | `int` | The HTTP status value |
| `error` | `String` | `HttpStatus.getReasonPhrase()`, e.g. `Conflict` |
| `message` | `String` | The exact message listed in the tables above |

Example — a 409 from `POST /api/accounts`:

```json
{
  "timestamp": "2026-11-02T10:15:30.123456",
  "status": 409,
  "error": "Conflict",
  "message": "You can have at most 5 accounts"
}
```

## Database

**Database name:** `account_db` — `spring.datasource.url: jdbc:postgresql://localhost:5433/account_db` (there is no environment placeholder in the YAML; override it with `SPRING_DATASOURCE_URL`). Created by `postgres/init/01-create-databases.sql` (`CREATE DATABASE account_db;`) in the PostgreSQL 16 container `banking-postgres`, published on host port **5433**. The schema is managed by Hibernate with `spring.jpa.hibernate.ddl-auto: update`.

### Table `accounts`

Entity `com.banking.account.entity.Account`, with `indexes = @Index(name = "idx_accounts_user", columnList = "userId")`.

| Column | Type | Null | Default / Notes |
|---|---|---|---|
| `account_number` | `character varying(50)` | no | PK; Java field `accountNumber` |
| `account_type` | `character varying(20)` | no | Java field `accountType`; stored as a string (`@Enumerated(EnumType.STRING)`) |
| `created_at` | `timestamp(6) without time zone` | no | `@PrePersist onCreate()` → `LocalDateTime.now()`; `updatable = false` |
| `currency` | `character varying(3)` | no | e.g. `USD` |
| `status` | `character varying(20)` | no | Java field `status`; stored as a string (`@Enumerated(EnumType.STRING)`) |
| `user_id` | `bigint` | no | Java field `userId`; indexed by `idx_accounts_user` |
| `failure_reason` | `character varying(255)` | yes | Java field `failureReason`; only set for `OPENING_FAILED` |

There is **no balance column** on this table.

Enums:

- `com.banking.account.entity.AccountType` → `SAVINGS`, `CURRENT`
- `com.banking.account.entity.AccountStatus` → `PENDING`, `ACTIVE`, `BLOCKED`, `OPENING_FAILED`

The database also adds the check constraints `accounts_account_type_check` (`SAVINGS`, `CURRENT`) and `accounts_status_check` (`PENDING`, `ACTIVE`, `BLOCKED`, `OPENING_FAILED`).

**Schema-repair note** (`postgres/init/04-repair-schema.sql`): the `accounts_status_check` constraint is recreated to allow exactly those four values:

```sql
ALTER TABLE accounts DROP CONSTRAINT IF EXISTS accounts_status_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_status_check CHECK (status IN (
    'PENDING', 'ACTIVE', 'BLOCKED', 'OPENING_FAILED'
));
```

The comment in that script explains the history: an older constraint allowed only `ACTIVE` and `BLOCKED`, so creating a `PENDING` account failed with *"new row for relation accounts violates check constraint accounts_status_check"*. The script is idempotent and is run on every start by `run-all.sh`.

### Table `outbox_events`

Entity `com.banking.account.messaging.OutboxEvent` — the same mapping as the one in `user-service`, with `indexes = @Index(name = "idx_outbox_pending", columnList = "publishedAt, createdAt")`.

| Column | Type | Null | Default / Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK, assigned with `UUID.randomUUID()` |
| `attempts` | `integer` | no | Default `0`; incremented by `recordFailure` |
| `created_at` | `timestamp(6) with time zone` | no | `@PrePersist` → `Instant.now()`; `updatable = false` |
| `envelope` | `text` | no | The serialised JSON event envelope |
| `event_type` | `character varying(100)` | no | e.g. `AccountOpened` |
| `last_error` | `character varying(500)` | yes | The failure reason, truncated to 500 characters |
| `message_key` | `character varying(100)` | no | The Kafka message key |
| `published_at` | `timestamp(6) with time zone` | yes | `null` until published |
| `topic` | `character varying(100)` | no | e.g. `account.events` |

### Table `processed_events`

Entity `com.banking.account.messaging.ProcessedEvent`, `@Entity @Table(name = "processed_events")`.

| Column | Type | Null | Default / Notes |
|---|---|---|---|
| `id` | `character varying(160)` | no | PK; the value is `"<handler>:<eventId>"` |
| `processed_at` | `timestamp(6) with time zone` | no | Set by the native insert (`now()`) |

### Repositories

`AccountRepository extends JpaRepository<Account, String>`:

| Method | Purpose |
|---|---|
| `List<Account> findByUserIdAndStatusNotOrderByCreatedAtAsc(Long userId, AccountStatus status)` | The user's accounts, oldest first, excluding one status |
| `long countByUserIdAndStatusNot(Long userId, AccountStatus status)` | The per-user account count |
| `long countByStatus(AccountStatus status)` | Backs the `accounts.pending` gauge |
| `Optional<Account> findByAccountNumberAndUserId(String accountNumber, Long userId)` | Ownership-aware lookup |
| `List<Account> findByAccountNumberIn(Collection<String> accountNumbers)` | Batch lookup for the internal endpoint |
| `int failStalePending(AccountStatus from, AccountStatus to, String reason, LocalDateTime cutoff)` | *"One atomic statement, so two running instances cannot both handle the same account."* |

`OutboxRepository extends JpaRepository<OutboxEvent, UUID>`:

| Method | Purpose |
|---|---|
| `List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()` | Batch of 50, oldest first, with `@Lock(PESSIMISTIC_WRITE)` and lock timeout `-2` |
| `long countByPublishedAtIsNull()` | Backs the `outbox.pending.events` gauge |
| `int deletePublishedBefore(Instant cutoff)` | Removes published events older than the cutoff |

`ProcessedEventRepository extends JpaRepository<ProcessedEvent, String>`:

| Method | Purpose |
|---|---|
| `int insertIfAbsent(String id)` | Native `insert into processed_events (id, processed_at) values (:id, now()) on conflict (id) do nothing` — returns `1` for the first caller and `0` for every later one |
| `int deleteOlderThan(Instant cutoff)` | Removes rows older than the cutoff |

## Kafka Topics

**Topic constants** — `com.banking.account.messaging.TopicNames`:

| Constant | Topic |
|---|---|
| `ACCOUNT_COMMANDS` | `account.commands` |
| `ACCOUNT_EVENTS` | `account.events` |
| `CBS_COMMANDS` | `cbs.commands` |
| `CBS_EVENTS` | `cbs.events` |
| `DLT_SUFFIX` | `".DLT"` — a message that failed all its retries is parked on `"<topic>.DLT"` |

### Publishes to

#### `cbs.commands` — command `CreateLedgerAccount`

Published by `AccountServiceImpl.create(...)` inside the account-creation transaction. Message key = the account number; `correlationId` = the account number; `causationId` = `null`.

Payload — `com.banking.account.messaging.payload.CreateLedgerAccountPayload`:

| Field | Type | Notes |
|---|---|---|
| `accountNumber` | `String` | e.g. `ACC-12345678` |
| `currency` | `String` | `app.account.default-currency` → `USD` |
| `openingBalance` | `BigDecimal` | `@JsonFormat(shape = JsonFormat.Shape.STRING)` → serialised as a **JSON string**; `app.account.demo-opening-balance` (default `0.00`), `null` → `BigDecimal.ZERO.setScale(2)` |

The core banking service parses the same command as `CreateLedgerAccountCommand(String accountNumber, String currency, BigDecimal openingBalance)`.

#### `account.events` — five event types

Published by `AccountCommandService` and `AccountOpeningService`. The message key is the `transferId` for the validation events and the account number for `AccountOpened`; `correlationId` is the `transferId` or the account number; `causationId` is the `eventId` of the command that triggered them.

| Event type | Published by | Payload record | Fields (name / type) |
|---|---|---|---|
| `TransferValidated` | `AccountCommandService.validateTransfer` | `TransferValidatedPayload` | `transferId` `String`; `currency` `String`; `sameOwner` `boolean` |
| `TransferRejected` | `AccountCommandService.validateTransfer` | `RejectionPayload` | `transferId` `String`; `reasonCode` `String`; `reason` `String` |
| `CashAccountValidated` | `AccountCommandService.validateCashAccount` | `CashAccountValidatedPayload` | `transferId` `String`; `currency` `String` |
| `CashAccountRejected` | `AccountCommandService.validateCashAccount` | `RejectionPayload` | `transferId` `String`; `reasonCode` `String`; `reason` `String` |
| `AccountOpened` | `AccountOpeningService.ledgerAccountCreated` | `AccountOpenedPayload` | `accountNumber` `String`; `userId` `Long`; `accountType` `String`; `currency` `String` |

Rejection reason codes used by `AccountCommandService`:

| `reasonCode` | Reason text | When |
|---|---|---|
| `SAME_ACCOUNT` | `Source and destination are the same account` | `fromAccountNumber == toAccountNumber` |
| `ACCOUNT_NOT_FOUND` | `Account not found: <number>` | The source or destination account does not exist |
| `NOT_OWNER` | `You do not own the source account` / `You do not own this account` | The caller is not the owner |
| `ACCOUNT_INACTIVE` | `Account is not active` | The status is not `ACTIVE` |
| `CURRENCY_MISMATCH` | `Currency mismatch between accounts` | The source and destination currencies differ |

### Consumes from

#### `account.commands` — `AccountCommandsConsumer`

`@KafkaListener(topics = TopicNames.ACCOUNT_COMMANDS)` → `onMessage(ConsumerRecord<String,String>)`. The envelope is parsed by `EventParser.parse`; unknown event types are **ignored** (`log.debug("Ignoring unknown command type {}", ...)`).

| Event type | Handler | Payload record | Payload fields (name / type) |
|---|---|---|---|
| `ValidateTransfer` | `AccountCommandService.validateTransfer` | `ValidateTransferCommand` (`@JsonIgnoreProperties(ignoreUnknown = true)`) | `transferId` `String`; `userId` `Long`; `fromAccountNumber` `String`; `toAccountNumber` `String` |
| `ValidateCashAccount` | `AccountCommandService.validateCashAccount` | `ValidateCashAccountCommand` (`@JsonIgnoreProperties(ignoreUnknown = true)`) | `transferId` `String`; `userId` `Long`; `accountNumber` `String`; `operation` `String` (`DEPOSIT` or `WITHDRAWAL`) |

The producers (`fund-transfer-service/.../saga/TransferSagaEngine`) send these commands with an additional `amount` field (`BigDecimal`, `@JsonFormat(shape = STRING)`) that this service ignores, thanks to `@JsonIgnoreProperties(ignoreUnknown = true)`.

#### `cbs.events` — `CbsEventsConsumer`

`@KafkaListener(topics = TopicNames.CBS_EVENTS)` → `onMessage(ConsumerRecord<String,String>)`.

| Event type | Handler | Payload record | Payload fields (name / type) |
|---|---|---|---|
| `LedgerAccountCreated` | `AccountOpeningService.ledgerAccountCreated` | `LedgerAccountCreatedEvent` (`@JsonIgnoreProperties(ignoreUnknown = true)`) | `accountNumber` `String`; `currency` `String` |
| `LedgerAccountCreationFailed` | `AccountOpeningService.ledgerAccountCreationFailed` | `LedgerAccountCreationFailedEvent` (`@JsonIgnoreProperties(ignoreUnknown = true)`) | `accountNumber` `String`; `reasonCode` `String`; `reason` `String` |

All other `cbs.events` types (`WithdrawCompleted`, `DepositCompleted`, `BalanceChecked`, reversals, …) are ignored — the comment says *"Withdraw, deposit and reversal results are for Fund Transfer."*

### Event envelope

`com.banking.account.messaging.EventEnvelope` (a record annotated `@JsonIgnoreProperties(ignoreUnknown = true)`):

| Field | Type | Value |
|---|---|---|
| `eventId` | `String` | UUID string |
| `eventType` | `String` | See the tables above |
| `version` | `int` | Always `1` |
| `correlationId` | `String` | The `transferId` or the account number |
| `causationId` | `String` | Nullable; the `eventId` of the triggering command |
| `occurredAt` | `Instant` | `Instant.now()` at publish time |
| `producer` | `String` | `app.messaging.producer-name` → `account-service` |
| `payload` | `JsonNode` | The event payload |

### Kafka client configuration

| Property | Value |
|---|---|
| `spring.kafka.bootstrap-servers` | `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` |
| `spring.kafka.consumer.group-id` | `account-service` |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` |
| `spring.kafka.consumer.enable-auto-commit` | `false` |
| `spring.kafka.listener.ack-mode` | `record` |
| `spring.kafka.listener.concurrency` | `3` |
| `spring.kafka.producer.acks` | `all` |
| `spring.kafka.producer.properties.enable.idempotence` | `true` |
| `spring.kafka.producer.properties.linger.ms` | `0` |
| `spring.kafka.producer.properties.max.block.ms` | `15000` |
| `spring.kafka.producer.properties.request.timeout.ms` | `5000` |
| `spring.kafka.producer.properties.delivery.timeout.ms` | `10000` |

### Consumer error handling and the dead-letter topic

`com.banking.account.messaging.KafkaConsumerConfig` defines a `DefaultErrorHandler`:

| Aspect | Detail |
|---|---|
| Recoverer | `DeadLetterPublishingRecoverer` publishes to `"<original topic>" + ".DLT"` on the same partition |
| Back-off | `ExponentialBackOffWithMaxRetries(4)` — initial interval `1000` ms, multiplier `2.0`, max interval `10_000` ms (retries after 1 s, 2 s, 4 s, 8 s) |
| Not retryable | `handler.addNotRetryableExceptions(InvalidEventException.class)` — a malformed message is **not** retried, it goes straight to the DLT |

Topic retention (from `event-bus-service/.../catalog/TopicCatalog.java`): 3 partitions per topic, 7-day retention and a 14-day DLT retention for `account.commands`, `account.events`, `cbs.commands` and `cbs.events`.

### Outbox relay

`com.banking.account.messaging.OutboxRelay` — the same behaviour as the `user-service` relay:

| Behaviour | Detail |
|---|---|
| Poll | `@Scheduled(fixedDelayString = "${app.messaging.relay-interval-ms:1000}")` |
| Batch | Up to 50 pending events |
| Publish | `kafka.send(topic, messageKey, envelope).get(30, TimeUnit.SECONDS)` |
| On failure | `recordFailure(reason)` (truncated to 500 chars); the batch stops so ordering is kept |
| Cleanup | `@Scheduled(cron = "0 15 3 * * *")` deletes published events older than 7 days |
| Meters | `outbox.events.published`, `outbox.publish.failures`, `outbox.pending.events` |

## Configuration

**Files:** `src/main/resources/application.yaml` (main) and `src/main/resources/application-local.yml` (profile `local`).

### Server, Spring and Eureka properties (application.yaml)

| Name | Default | Meaning |
|---|---|---|
| `server.port` | `8081` | HTTP port |
| `management.server.port` | `18081` | Private Actuator/metrics port |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | Actuator endpoints exposed |
| `management.metrics.distribution.percentiles-histogram.http.server.requests` | `true` | HTTP request histograms |
| `spring.application.name` | `account-service` | Eureka service name and load-balancer name |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5433/account_db` | JDBC URL (no env placeholder in the YAML) |
| `spring.datasource.username` | `${DB_USERNAME:banking_admin}` | Database user |
| `spring.datasource.password` | `${DB_PASSWORD:postgre2188}` | Database password |
| `spring.jpa.hibernate.ddl-auto` | `update` | Hibernate updates the schema on start |
| `spring.jpa.open-in-view` | `false` | OSIV disabled |
| `spring.kafka.bootstrap-servers` | `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` | Kafka brokers |
| `spring.kafka.consumer.group-id` | `account-service` | Consumer group |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | Where to start when there is no committed offset |
| `spring.kafka.consumer.enable-auto-commit` | `false` | Manual/record acknowledgement |
| `spring.kafka.listener.ack-mode` | `record` | Acknowledge per record |
| `spring.kafka.listener.concurrency` | `3` | Consumer threads |
| `spring.kafka.producer.acks` | `all` | All replicas must acknowledge |
| `spring.kafka.producer.properties.enable.idempotence` | `true` | Idempotent producer |
| `spring.kafka.producer.properties.linger.ms` | `0` | Send immediately |
| `spring.kafka.producer.properties.max.block.ms` | `15000` | Max time to block on a metadata/send request |
| `spring.kafka.producer.properties.request.timeout.ms` | `5000` | Request timeout |
| `spring.kafka.producer.properties.delivery.timeout.ms` | `10000` | Delivery timeout |
| `eureka.client.service-url.defaultZone` | `${EUREKA_URL:http://localhost:8761/eureka}` | Discovery server |
| `eureka.instance.prefer-ip-address` | `true` | Register the IP, not the hostname |

Note that `application.yaml` has **no** `spring.config.import` (unlike `user-service`), so a local `.env` file is **not** auto-loaded here.

### Custom `app.*` properties

| Name | Default | Meaning |
|---|---|---|
| `app.cbs.base-url` | `http://core-banking-service` | Eureka service name of the core banking service, resolved by the load balancer. Used for `GET /internal/cbs/balances`. |
| `app.account.max-per-user` | `5` | Maximum number of accounts per user (accounts with status `OPENING_FAILED` do not count) |
| `app.account.default-currency` | `USD` | Currency assigned to every new account |
| `app.account.pending-timeout-minutes` | `10` | How long an account may stay `PENDING` before the sweeper marks it `OPENING_FAILED` |
| `app.account.demo-opening-balance` | `${ACCOUNT_DEMO_BALANCE:0.00}` | **DEV ONLY** — the opening balance sent to CBS so transfers can be tested. The YAML comment says: *"Set ACCOUNT_DEMO_BALANCE=0 (or remove this) for anything real."* |
| `app.messaging.producer-name` | `account-service` | The `producer` field of every event envelope |
| `app.messaging.relay-interval-ms` | `1000` | Outbox relay poll interval |

`app.account.*` is bound to `com.banking.account.config.AccountProperties` (`@ConfigurationProperties(prefix = "app.account")`, a record): `int maxPerUser, String defaultCurrency, BigDecimal demoOpeningBalance`.

`app.messaging.*` is bound to `com.banking.account.messaging.MessagingProperties` (`@ConfigurationProperties(prefix = "app.messaging")`, a record): `String producerName`.

`app.account.pending-timeout-minutes` is read directly with `@Value("${app.account.pending-timeout-minutes:10}")` in `PendingAccountSweeper`, not through `AccountProperties`.

### The `local` profile (`application-local.yml`)

```yaml
eureka:
  client:
    enabled: false

spring:
  cloud:
    discovery:
      client:
        simple:
          instances:
            core-banking-service:
              - uri: http://localhost:8084
```

With the `local` profile, Eureka is disabled and the core banking service is resolved statically to `http://localhost:8084`.

### Environment variables

| Name | Default | Used for |
|---|---|---|
| `DB_USERNAME` | `banking_admin` | `spring.datasource.username` |
| `DB_PASSWORD` | `postgre2188` | `spring.datasource.password` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `spring.kafka.bootstrap-servers` |
| `EUREKA_URL` | `http://localhost:8761/eureka` | Eureka default zone |
| `ACCOUNT_DEMO_BALANCE` | `0.00` | `app.account.demo-opening-balance` |
| `SERVER_PORT` | — | Spring Boot standard; `run-all.sh` sets it to `8081` |
| `SPRING_DATASOURCE_URL` | — | Spring Boot relaxed-binding override; `run-all.sh` sets it to `jdbc:postgresql://127.0.0.1:5433/account_db` |
| `SPRING_DATASOURCE_USERNAME` | — | `run-all.sh` sets it to `$DB_USER` (`banking_admin`) |
| `SPRING_DATASOURCE_PASSWORD` | — | `run-all.sh` sets it to `$DB_PASSWORD` |
| `SPRING_PROFILES_ACTIVE` | — | Set to `local` to use `application-local.yml`; `run-all.sh` explicitly unsets it |

#### Environment variables you must set

| Name | Why it matters |
|---|---|
| `SPRING_DATASOURCE_URL` (or edit `spring.datasource.url`) | The YAML has no placeholder, so the JDBC URL always points at `jdbc:postgresql://localhost:5433/account_db`. Set it when the database is elsewhere. |
| `DB_USERNAME` / `DB_PASSWORD` | The database credentials |
| `KAFKA_BOOTSTRAP_SERVERS` | The broker — without it neither the commands nor the events are delivered |
| `EUREKA_URL` | Needed unless the `local` profile is used, because `app.cbs.base-url` is resolved through Eureka |

`ACCOUNT_DEMO_BALANCE` and `SERVER_PORT` are optional.

## Behaviour Rules

### Account creation (`AccountServiceImpl.create`)

1. **Limit check:** `accountRepository.countByUserIdAndStatusNot(userId, AccountStatus.OPENING_FAILED) >= app.account.maxPerUser` → `MaxAccountsReachedException("You can have at most 5 accounts")` → **409 Conflict**. Accounts in `OPENING_FAILED` do **not** count towards the limit.
2. `currency = properties.defaultCurrency()` (`USD`).
3. `opening = properties.demoOpeningBalance() == null ? BigDecimal.ZERO.setScale(2) : properties.demoOpeningBalance().setScale(2)`.
4. **Account number generation:** up to `MAX_NUMBER_ATTEMPTS = 5` attempts. `AccountNumberGenerator.next()` returns `"ACC-" + (10_000_000 + random.nextInt(90_000_000))`, i.e. `ACC-` followed by **8 digits never starting with 0** (range `10000000`–`99999999`), produced with `java.security.SecureRandom`. If `accountRepository.existsById(number)` is true, the loop continues with a new number. After 5 failed attempts → `AccountCreationFailedException("Could not create the account, please try again")` → **503**.
5. The account is saved with `status = AccountStatus.PENDING` and `failureReason = null`.
6. The `CreateLedgerAccount` command is published to `cbs.commands` **in the same transaction** (the outbox writer uses `Propagation.MANDATORY`).
7. Response: **202 Accepted** with `AccountResponse(accountNumber, accountType, 0.00, currency, "PENDING", null)`.

### Account status lifecycle

| From | To | Trigger |
|---|---|---|
| — | `PENDING` | The account is created via `POST /api/accounts` |
| `PENDING` | `ACTIVE` | A `LedgerAccountCreated` event from `cbs.events` |
| `PENDING` | `OPENING_FAILED` | A `LedgerAccountCreationFailed` event from `cbs.events`, or the pending sweeper after the timeout |
| `OPENING_FAILED` | `ACTIVE` | A **late** `LedgerAccountCreated` event still activates the account — the source comment explains: *"OPENING_FAILED is included on purpose: a late answer after the timeout still activates the account, so a ledger account never stays without an owner."* |
| Any other status | Unchanged | A `LedgerAccountCreated` for an `ACTIVE`/`BLOCKED` account is ignored (counter `result=ignored`) |

The failure reason is truncated to 255 characters; the default reason when the event carries none is `The ledger account could not be created`.

### The pending sweeper (`PendingAccountSweeper`)

| Aspect | Detail |
|---|---|
| Schedule | `@Scheduled(fixedDelay = 60_000)` — every 60 seconds |
| Action | The atomic query `failStalePending(PENDING → OPENING_FAILED, "Opening timed out. Please try again", now - pendingTimeoutMinutes)` |
| Timeout | `app.account.pending-timeout-minutes` = **10** |
| Log | `{} account(s) waited more than {} minutes for the ledger and were marked OPENING_FAILED` |

### Balance handling

| Rule | Detail |
|---|---|
| No balances are stored | The service **never stores balances**; `accounts` has no balance column |
| `listForUser` | Balances are fetched only for accounts whose status is **not** `PENDING` (i.e. `ACTIVE` or `BLOCKED`); `PENDING` accounts report `0.00` |
| `getOne` | The balance is fetched only when the status is `ACTIVE` or `BLOCKED` |
| Missing balance | `log.error("No ledger balance for account {}")` and `CbsUnavailableException("Balance temporarily unavailable, please try again")` → **503** |
| CBS unreachable | `CbsClientImpl` wraps any `RestClientException`/`IllegalStateException` into `CbsUnavailableException("Balances are temporarily unavailable, please try again")` → **503** |
| Zero value | `BigDecimal.ZERO.setScale(2)` |

### Command validation (`com.banking.account.messaging.CommandValidation`)

These helpers throw `InvalidEventException`, which is **not retryable** and therefore goes straight to the DLT:

| Helper | Rule | Error message |
|---|---|---|
| `text(String value, String field, int maxLength)` | Not null, not blank, `value.trim().length() <= maxLength` | `<field> is missing or too long` |
| `required(Long value, String field)` | Not null | `<field> is missing` |
| `oneOf(String value, String field, String... allowed)` | The value must equal one of the allowed strings (trimmed) | `<field> must be one of ` + the allowed values joined with `, ` |

Applied lengths: `transferId` ≤ **80**, `accountNumber` ≤ **50**, `fromAccountNumber` ≤ **50**, `toAccountNumber` ≤ **50**; `operation` must be `DEPOSIT` or `WITHDRAWAL`.

`EventParser` throws `InvalidEventException` for:

| Situation | Message |
|---|---|
| An empty or blank message | `Empty message` |
| Invalid JSON | `Message is not valid JSON` |
| A missing `eventId` or `eventType` | `Message has no eventId or eventType` |
| A null payload | `Message has no payload` |
| A payload that does not match the expected type | `Payload does not match ` + the event type |

### Transfer validation rules (`AccountCommandService.validateTransfer`)

Checked in this order:

1. `fromAccountNumber == toAccountNumber` → reject `SAME_ACCOUNT`.
2. The source account is not found → reject `ACCOUNT_NOT_FOUND` (`Account not found: <from>`).
3. The destination account is not found → reject `ACCOUNT_NOT_FOUND` (`Account not found: <to>`).
4. The caller does not own the source account → reject `NOT_OWNER` (`You do not own the source account`).
5. Either account is not `ACTIVE` → reject `ACCOUNT_INACTIVE` (`Account is not active`).
6. The two accounts have different currencies → reject `CURRENCY_MISMATCH` (`Currency mismatch between accounts`).

On success → `TransferValidated(transferId, fromAccount.getCurrency(), sameOwner)` where `sameOwner = userId.equals(toAccount.getUserId())`.

### Cash-account validation rules (`AccountCommandService.validateCashAccount`)

1. The account is not found → reject `ACCOUNT_NOT_FOUND` (`Account not found: <number>`).
2. The caller does not own the account → reject `NOT_OWNER` (`You do not own this account`).
3. The account is not `ACTIVE` → reject `ACCOUNT_INACTIVE` (`Account is not active`).

On success → `CashAccountValidated(transferId, account.getCurrency())`.

### Idempotency handling

| Aspect | Detail |
|---|---|
| Guard | `ProcessedEventGuard.firstTime(handler, eventId)` — `@Transactional(propagation = Propagation.MANDATORY)`, joins the caller's transaction, and inserts `"<handler>:<eventId>"` with `insert ... on conflict (id) do nothing`. It returns `true` only for the **first** handler of that event id. |
| Handlers | `account-commands` (for both `ValidateTransfer` and `ValidateCashAccount`) and `cbs-events` (for both ledger events) |
| Duplicates | A duplicate is skipped and counted (`result=duplicate`) |
| Cleanup | `ProcessedEventCleaner` — `@Scheduled(cron = "0 30 3 * * *")` deletes records older than **14 days** (*"Topics keep messages for 7 days, so ids older than 14 days can no longer be redelivered."*) |

### Metrics

| Meter | Type | Tags / meaning |
|---|---|---|
| `accounts.pending` | gauge | `accounts.countByStatus(AccountStatus.PENDING)` — *"Accounts waiting for CBS to create their ledger account"* |
| `account.commands` | counter | Tags `command` (`ValidateTransfer` / `ValidateCashAccount`) and `result` (`validated` / `rejected` / `duplicate`) |
| `account.opening` | counter | Tag `result` ∈ `activated` / `failed` / `ignored` / `unknown` / `duplicate` |
| `outbox.events.published` | counter | |
| `outbox.publish.failures` | counter | |
| `outbox.pending.events` | gauge | |

### Error code → HTTP status mapping (`GlobalExceptionHandler`)

| Exception | HTTP status | `error` | Message |
|---|---|---|---|
| `AccountNotFoundException` | **404 NOT_FOUND** | `Not Found` | `ex.getMessage()` |
| `MaxAccountsReachedException` | **409 CONFLICT** | `Conflict` | `ex.getMessage()` |
| `CbsUnavailableException` | **503 SERVICE_UNAVAILABLE** | `Service Unavailable` | `ex.getMessage()` |
| `AccountCreationFailedException` | **503 SERVICE_UNAVAILABLE** | `Service Unavailable` | `ex.getMessage()` |
| `MissingRequestHeaderException` | **401 UNAUTHORIZED** | `Unauthorized` | `Missing user identity` |
| `MethodArgumentTypeMismatchException` | **400 BAD_REQUEST** | `Bad Request` | `Invalid value for ` + the parameter name |
| `MethodArgumentNotValidException` | **400 BAD_REQUEST** | `Bad Request` | `<field>: <defaultMessage>` joined with `; ` |
| `HttpMessageNotReadableException` | **400 BAD_REQUEST** | `Bad Request` | `Malformed request body or invalid accountType (SAVINGS or CURRENT)` |
| `NoResourceFoundException` | **404 NOT_FOUND** | `Not Found` | `Not found` |
| `Exception` (catch-all) | **500 INTERNAL_SERVER_ERROR** | `Internal Server Error` | `Something went wrong` (the stack trace is logged: `Unhandled error in the account service`) |

### Rate limiting

There is **no** rate limiting inside this service. The API gateway has no `/api/accounts/**` rule in `app.rate-limit.rules` — only `/api/auth/register`, `/api/auth/login` and `/api/auth/verify` are limited. Authentication is enforced by the gateway (`JwtAuthenticationFilter`, order `-100`), which also strips any client-supplied `X-User-Id` / `X-User-Email` / `X-User-Roles` headers and re-adds them from the JWT.

### Token generation

None — this service has no JWT and no Spring Security dependency (`pom.xml` contains no `spring-boot-starter-security`).

## Running It

**Prerequisites**

| Requirement | Detail |
|---|---|
| Java | 17 |
| Maven | 3.6+ (or use the bundled `./mvnw`) |
| PostgreSQL | 16, reachable on `127.0.0.1:5433`, with the `account_db` database |
| Kafka | Broker on `localhost:9092`; the topics `account.commands`, `account.events`, `cbs.commands` and `cbs.events` are created by `event-bus-service` |
| Eureka | `discovery-service` on port 8761 (not needed with the `local` profile) |
| `core-banking-service` | Port 8084 — needed for balances and for opening ledger accounts |

**Build**

```bash
cd /home/bs01092/Projects/banking-microservices/account-service
mvn clean package -DskipTests        # or: ./mvnw clean package -DskipTests
# jar: target/account-service-0.0.1-SNAPSHOT.jar
```

**Run**

```bash
java -jar target/account-service-0.0.1-SNAPSHOT.jar

# profile local (no Eureka, CBS resolved statically to http://localhost:8084):
java -jar target/account-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

**Environment variables**

```bash
export SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5433/account_db'
export DB_USERNAME='banking_admin'
export DB_PASSWORD='postgre2188'
export KAFKA_BOOTSTRAP_SERVERS='localhost:9092'
export EUREKA_URL='http://localhost:8761/eureka'
export ACCOUNT_DEMO_BALANCE='0.00'
export SERVER_PORT='8081'
```

**Check it is healthy**

```bash
curl -s http://127.0.0.1:18081/actuator/health
# {"status":"UP"}

curl -s http://127.0.0.1:18081/actuator/prometheus | head
```

The Actuator port is private on purpose: the management port (`18081`) serves health, info and prometheus, while the public port (`8081`) must not expose `/actuator`.

**Stop it**

```bash
# if started in the foreground
Ctrl+C

# whole system
cd /home/bs01092/Projects/banking-microservices
./stop-all.sh
```

**Whole-system start** (from the repository root): `./run-all.sh`, or `./run-all.sh --build` to rebuild each jar first. `run-all.sh` starts `account-service` on port `8081` with database `account_db`. Health and metrics check: `./check-all.sh` (expects `account-service` on `8081` / metrics on `18081`).

## API Quick Reference (curl examples)

The public endpoints need the `X-User-Id` header, which the gateway normally sets from the JWT. When calling the service directly you must supply it yourself.

Create an account (through the gateway, with a token):

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"correct-horse"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

curl -i -X POST http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"accountType":"SAVINGS"}'
```

List the caller's accounts:

```bash
curl -i http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN"
```

Get one account:

```bash
curl -i http://localhost:8080/api/accounts/ACC-12345678 \
  -H "Authorization: Bearer $TOKEN"
```

# Account Service

**Part of the Banking Microservices Project**

Developed and maintained by **Jahid Hasan**

- **GitHub:** [Md-Jahid-Hasan-10](https://github.com/Md-Jahid-Hasan-10)
- **Repository:** [account-service](https://github.com/Md-Jahid-Hasan-10/account-service)
- **Project:** Banking Microservices
- **Service Port:** `8081`
- **Actuator/Metrics Port:** `18081`

---

The `account-service` is responsible for managing customer bank accounts within the Banking Microservices platform. It handles account creation, account ownership, account validation, account lifecycle management, and communication with the Core Banking System through Kafka-based event-driven architecture.



Call the service directly (bypassing the gateway), supplying the header by hand:

```bash
curl -i http://localhost:8081/api/accounts \
  -H 'X-User-Id: 1'

curl -i -X POST http://localhost:8081/api/accounts \
  -H 'X-User-Id: 1' \
  -H 'Content-Type: application/json' \
  -d '{"accountType":"CURRENT"}'
```

Internal lookups (direct call; the gateway answers 404 for `/internal/**`):

```bash
curl -i http://localhost:8081/internal/accounts/ACC-12345678

curl -i 'http://localhost:8081/internal/accounts?accountNumbers=ACC-12345678,ACC-87654321'
```

## Configuration Reference (quick table)

| Knob | Default | Change it when |
|---|---|---|
| `server.port` | `8081` | The port is already taken |
| `management.server.port` | `18081` | The metrics port conflicts with another service |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5433/account_db` | The database is not on the default host/port |
| `DB_USERNAME` | `banking_admin` | The database user differs |
| `DB_PASSWORD` | `postgre2188` | The database password differs |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | The broker is elsewhere |
| `EUREKA_URL` | `http://localhost:8761/eureka` | The discovery server is elsewhere (or use the `local` profile) |
| `app.cbs.base-url` | `http://core-banking-service` | The core banking service has a different Eureka name |
| `app.account.max-per-user` | `5` | The per-user account cap should be different |
| `app.account.default-currency` | `USD` | New accounts should be opened in another currency |
| `app.account.pending-timeout-minutes` | `10` | A ledger account should be given more or less time to open |
| `app.account.demo-opening-balance` | `0.00` | **DEV ONLY** — set to 0 for anything real |
| `app.messaging.relay-interval-ms` | `1000` | The outbox should be polled more or less often |
| `app.messaging.producer-name` | `account-service` | The producer name in the envelope must change |
| `SPRING_PROFILES_ACTIVE` | — | Set to `local` to disable Eureka and resolve CBS statically |

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `401` with `Missing user identity` | The `X-User-Id` header is missing | Call through the API gateway with `Authorization: Bearer <token>`, or add `-H 'X-User-Id: <id>'` when calling the service directly |
| `400` with `Invalid value for X-User-Id` | The header is not a number | `X-User-Id` must be the numeric user id |
| `404` with `Account not found: ACC-12345678` | The account does not exist, or it belongs to another user | `findByAccountNumberAndUserId` hides other people's accounts as "not found" |
| `409` with `You can have at most 5 accounts` | The user already has 5 accounts | Accounts in `OPENING_FAILED` do not count, so a failed opening can be replaced |
| `503` with `Balances are temporarily unavailable, please try again` | The core banking service could not be reached | Check that `core-banking-service` is up and that `app.cbs.base-url` resolves through Eureka; without the `local` profile Eureka must be running |
| `503` with `Balance temporarily unavailable, please try again` | An `ACTIVE`/`BLOCKED` account has no ledger balance | The log contains `No ledger balance for account <number>`; the ledger account is missing in CBS |
| The account stays `PENDING` forever | The `LedgerAccountCreated` event never arrived | Check `cbs.events` on the broker; after 10 minutes the sweeper marks it `OPENING_FAILED` with `Opening timed out. Please try again` |
| The account is `OPENING_FAILED` but a ledger account exists in CBS | The sweeper fired before the CBS answer arrived | A late `LedgerAccountCreated` still activates the account — the account turns `ACTIVE` on its own |
| The account is `OPENING_FAILED` right after creation | The `accounts_status_check` constraint was still the old one | Run `postgres/init/04-repair-schema.sql` (idempotent; `run-all.sh` runs it on every start) |
| `400` with `Malformed request body or invalid accountType (SAVINGS or CURRENT)` | The body is not JSON, or `accountType` is not one of the two enum values | Use `{"accountType":"SAVINGS"}` or `{"accountType":"CURRENT"}` |
| `outbox.pending.events` keeps growing | The relay cannot reach Kafka | Check `KAFKA_BOOTSTRAP_SERVERS` and the broker; the relay logs the publish failure and stops the batch so ordering is kept |
| A command sits in `account.commands.DLT` | The message was malformed or failed 4 retries | `InvalidEventException` (for example `transferId is missing or too long`) is not retried and goes straight to the DLT |
| The same command is applied twice | The `processed_events` row was removed, or the event id changed | The guard inserts `"<handler>:<eventId>"` with `on conflict do nothing`; rows are cleaned up after 14 days |
| The service starts but cannot find the core banking service | Eureka is disabled or the CBS instance is not registered | Use the `local` profile, or start `discovery-service` and `core-banking-service` |
| `401` with `Invalid or expired token` from the gateway | The token was issued with a `JWT_SECRET` that differs from the gateway's | Set the same `JWT_SECRET` in `user-service` and `api-gateway` |

## Notes and Known Limitations

- **`LedgerAccountConflictException` is dead code.** The class exists in `src/main/java/com/banking/account/exception/` but is never thrown and never handled — a grep over the whole repository finds only the class declaration.
- **`app.account.demo-opening-balance` is DEV ONLY.** It gives every new account an opening balance so transfers can be tested. The YAML itself warns: *"Set ACCOUNT_DEMO_BALANCE=0 (or remove this) for anything real."* It is sent to CBS in the `CreateLedgerAccount` command as a JSON string.
- **No `.env` file and no `spring.config.import`.** Unlike `user-service`, this service does not auto-load a local `.env` file, and `.env` is not even in its `.gitignore` — its `.gitignore` is the stock Spring Initializr one plus `HELP.md`. Every value therefore comes from the environment or the command line.
- **The `local` profile must not be active in the integrated system.** `run-all.sh` explicitly unsets `SPRING_PROFILES_ACTIVE` (*"The 'local' profile must NOT be active in the integrated system."*), because `application-local.yml` disables Eureka and points CBS at `http://localhost:8084`.
- **There is no balance column.** Balances are fetched from CBS on every read of an `ACTIVE` or `BLOCKED` account, which makes the read path depend on CBS availability.
- **The `X-User-Id` header is trusted only because the gateway sets it.** The gateway's `JwtAuthenticationFilter` strips any client-supplied `X-User-Id` / `X-User-Email` / `X-User-Roles` headers and re-adds them from the JWT. Calling the service directly on port 8081 bypasses that protection, so treat port 8081 as internal-only.
- **No rate limiting in this service.** The gateway has no `/api/accounts/**` rule.
- **`/internal/**` is blocked by the gateway with a 404.** The gateway's `blocked-internal` route (`uri: no://op`) never forwards internal paths, and `JwtAuthenticationFilter` answers 404 for them. The internal endpoints are meant for service-to-service calls inside the cluster.
- **The `accounts` table has only one index** (`idx_accounts_user` on `user_id`) besides the primary key.
- **A late CBS answer still activates an account.** `OPENING_FAILED` is deliberately included in the "can still become `ACTIVE`" set, so a ledger account never stays without an owner — but the failure reason is cleared at that point.
- **`account-service` is a separate Git repository** (remote `https://github.com/yamin-2001/account-service.git`, branch `main`).

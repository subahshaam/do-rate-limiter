# Distributed Rate Limiter

## Summary / Deliverable

A Java 21 / Spring Boot REST API for per-tenant, per-resource quotas. It supports fixed-window and token-bucket rules, stores shared state in Redis, and uses atomic Lua scripts so concurrent requests across app replicas cannot overspend quota.

- Public endpoint: `https://68-183-94-203.sslip.io`
- Public API routes require `Authorization: Bearer <API_KEY>`.
- Source and CI: [subahshaam/do-rate-limiter](https://github.com/subahshaam/do-rate-limiter)

## HLD

```mermaid
flowchart LR
  Client -->|API request + bearer key| HAProxy
  HAProxy --> App1[Spring Boot replica 1]
  HAProxy --> App2[Spring Boot replica 2]
  App1 --> Redis[(Shared Redis)]
  App2 --> Redis
```

HAProxy distributes requests to both application containers. Both replicas use the same Redis instance for rules and quota state. See [docs/architecture.md](docs/architecture.md) for request and rule-update flows.

## LLD

### Class diagram

```mermaid
classDiagram
  direction LR

  class ApiKeyFilter {
    +doFilterInternal(request, response, chain)
  }
  class RateLimitController {
    +evaluate(request) EvaluationResponse
    +quota(tenantId, resource) EvaluationResponse
    +listRules() List~RateLimitRule~
    +getRule(tenantId, resource) RateLimitRule
    +createRule(rule) RateLimitRule
    +updateRule(tenantId, resource, rule) RateLimitRule
    +deleteRule(tenantId, resource) ResponseEntity
  }
  class RateLimitService {
    +saveRule(rule) RateLimitRule
    +deleteRule(tenantId, resource) boolean
    +listRules() List~RateLimitRule~
    +getRule(tenantId, resource) RateLimitRule
    +evaluate(tenantId, resource) EvaluationResponse
    +getQuota(tenantId, resource) EvaluationResponse
  }
  class EvaluationRequest {
    +tenantId: String
    +resource: String
  }
  class EvaluationResponse {
    +allowed: boolean
    +remaining: long
    +resetAt: long
  }
  class RateLimitRule {
    +tenantId: String
    +resource: String
    +algorithm: RateLimitAlgorithm
    +sustainedLimit: int
    +burstCapacity: int
    +windowSeconds: long
  }
  class RateLimitAlgorithm {
    <<enumeration>>
    FIXED_WINDOW
    TOKEN_BUCKET
  }
  class QuotaSnapshotCalculator
  class RuleNotFoundException
  class ApiExceptionHandler
  class RedisConnectionFailureException
  class RedisConfiguration
  class StringRedisTemplate

  ApiKeyFilter ..> RateLimitController : authenticates before request handling
  RateLimitController --> RateLimitService : delegates
  RateLimitController ..> EvaluationRequest : accepts
  RateLimitController ..> EvaluationResponse : returns
  RateLimitService ..> RateLimitRule : reads and writes
  RateLimitService ..> RateLimitAlgorithm : selects algorithm
  RateLimitService ..> EvaluationResponse : creates
  RateLimitService ..> QuotaSnapshotCalculator : computes read-only snapshot
  RateLimitService ..> RuleNotFoundException : throws
  RateLimitService --> StringRedisTemplate : accesses rules and quota state
  RedisConfiguration ..> StringRedisTemplate : configures
  RateLimitRule --> RateLimitAlgorithm : uses
  ApiExceptionHandler ..> RedisConnectionFailureException : maps to HTTP 503
```

### Rate-limit evaluation

The primary rate-limiting endpoint is `POST /v1/evaluate`. It checks the quota for a `(tenantId, resource)` pair and atomically updates its shared Redis state. Public requests require a bearer API key.

**Public URL:** `POST https://68-183-94-203.sslip.io/v1/evaluate`

```bash
curl -sS -X POST "https://68-183-94-203.sslip.io/v1/evaluate" \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"tenantId":"tenant-a","resource":"search"}'
```

Request:

```json
{"tenantId":"tenant-a","resource":"search"}
```

Response:

```json
{"allowed":true,"remaining":9,"resetAt":1795241224075}
```

`allowed` tells the caller whether to proceed, `remaining` is the available quota, and `resetAt` is a Unix epoch timestamp in milliseconds. By default, the service uses a token bucket with a sustained limit of 10 requests per 60 seconds and a burst capacity of 20.

### Quota and rule endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/quota/{tenantId}/{resource}` | Inspect quota state without consuming quota |
| `GET` | `/v1/rules` | List configured rules |
| `GET` | `/v1/rules/{tenantId}/{resource}` | Read one rule |
| `POST` | `/v1/rules` | Create a rule |
| `PUT` | `/v1/rules/{tenantId}/{resource}` | Update a rule |
| `DELETE` | `/v1/rules/{tenantId}/{resource}` | Delete a rule and its quota state |

Rule configuration includes `algorithm`, `sustainedLimit`, `burstCapacity`, and `windowSeconds`.

### Algorithms and Redis state

- **Fixed window:** Redis stores a request counter and reset timestamp. It is simple, but may allow bursts on adjacent window boundaries. `burstCapacity` is unused for this algorithm.
- **Token bucket:** Redis stores token balance and last-refill time. Tokens replenish at `sustainedLimit / windowSeconds`, up to `burstCapacity`.
- Rule values are stored under `rate-limit:rule:*`; quota state is stored under `rate-limit:state:*`.
- A Lua script performs each evaluation’s read/check/update atomically in Redis. Redis serializes script execution, bounding contention to that single operation.

## Redis and Data Storage

Quota and rule data are scoped by the `(tenantId, resource)` pair. Each pair gets its own rule key and quota-state key; different tenants do not consume one another's capacity, while both application replicas use the same Redis keys for the same pair.

| Redis key | Stored data |
|---|---|
| `rate-limit:rule:{tenantId}:{resource}` | JSON-serialized rule configuration |
| `rate-limit:rules` | Set indexing configured rule keys for listing |
| `rate-limit:state:{tenantId}:{resource}` | Current algorithm state |

Fixed-window state stores a request `count` and window `reset` timestamp; the key expires at the reset boundary. Token-bucket state stores the current `tokens` and last `updated` timestamp, with a 24-hour idle expiry. Redis Lua scripts atomically read and update this state, so replicas cannot race by maintaining separate local counters. Rule reads are not cached.

### Example structures

Rule:

```json
{"tenantId":"tenant-a","resource":"search","algorithm":"FIXED_WINDOW","sustainedLimit":3,"burstCapacity":3,"windowSeconds":60}
```

Fixed-window state:

```json
{"count":2,"reset":1795241224075}
```

Token-bucket state:

```json
{"tokens":19,"updated":1795241224075}
```

Compose enables Redis AOF persistence and stores its files in the `redis_data` volume. This survives container restarts, but does not provide replication, failover, or an off-host backup. Deleting a rule also deletes that tenant/resource pair's quota state.

## Monitoring

Spring Boot Actuator exposes:

- `/actuator/health` — overall health, including Redis.
- `/actuator/health/readiness` — readiness; includes Redis health.
- `/actuator/health/liveness` — process liveness.

Health routes are public for probes. Missing or invalid API keys receive HTTP `401`. If Redis is unavailable, the service fails closed with HTTP `503` and `REDIS_UNAVAILABLE`. No metrics or tracing backend is configured in this demo.

## Maintainability

- `src/main/java/com/example/demo/RateLimitController.java` — HTTP API.
- `RateLimitService.java` — Redis rule/state access and Lua evaluations.
- `ApiKeyFilter.java` — bearer-key enforcement in the `public` profile.
- `QuotaSnapshotCalculator.java` — quota snapshot calculations.
- `src/test/java/com/example/demo/` — unit and HTTP/Redis integration tests.
- `docker-compose.deploy.yml`, `Dockerfile`, and `Caddyfile` — deployment stack.
- `.github/workflows/ci.yml` — CI test and package pipeline.

The API key is supplied via `RATE_LIMIT_API_KEY` and must be kept in an untracked `.env` file on the server. Never commit it or put it in a URL. The current deployment runs all containers on one Droplet; Redis replication/failover and multi-host availability are not configured. Rule reads are not cached.

## Test

Run tests with Java 21, Maven, and a Redis instance available at the configured `REDIS_HOST`/`REDIS_PORT`:

```bash
REDIS_HOST=localhost mvn verify
```

`mvn verify` runs algorithm boundary/refill unit tests, HTTP controller tests, Redis-backed CRUD and concurrency tests, Redis-unavailable behavior tests, and public-profile API-key tests. GitHub Actions starts Redis, runs the same verification, and packages the application on pushes and pull requests.

## Deployment

The DigitalOcean deployment uses one Droplet with Caddy, HAProxy, two Spring Boot containers, and Redis. The public URL is `https://68-183-94-203.sslip.io`; `/v1/**` requires a bearer key. HAProxy and Redis are not directly exposed publicly. The Droplet is provisioned with 2 vCPUs and 4 GB RAM; this is host capacity, not measured application usage.

The app key is mandatory in the server’s untracked `/opt/rate-limiter/.env`. Caddy obtains and renews the HTTPS certificate. The Droplet is a single-host failure domain and is billable while active. Do not remove bearer authentication or expose the internal app/Redis ports.

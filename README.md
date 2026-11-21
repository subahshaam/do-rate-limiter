# Distributed Rate Limiter

Java 21 / Spring Boot REST API using Redis Lua scripts for atomic, shared quota decisions across replicas.

## Run and test locally

Start Redis, then run unit and Redis-backed HTTP integration tests:

```bash
docker compose -f docker-compose.deploy.yml up -d redis
REDIS_HOST=localhost mvn verify
```

Run the complete local stack (two app replicas, Redis, and HAProxy):

```bash
docker compose -f docker-compose.deploy.yml up --build -d
curl http://localhost:8080/actuator/health
```

HAProxy listens on loopback only. To stop the stack, run `docker compose -f docker-compose.deploy.yml down`.

## API examples

Create a rule:

```bash
curl -X POST http://localhost:8080/v1/rules \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"tenant-a","resource":"search","algorithm":"FIXED_WINDOW","sustainedLimit":10,"burstCapacity":20,"windowSeconds":60}'
```

Evaluate:

```bash
curl -X POST http://localhost:8080/v1/evaluate \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"tenant-a","resource":"search"}'
```

Other endpoints: `GET /v1/quota/{tenantId}/{resource}`, `GET /v1/rules`, `GET /v1/rules/{tenantId}/{resource}`, `PUT /v1/rules/{tenantId}/{resource}`, and `DELETE /v1/rules/{tenantId}/{resource}`.

## Algorithm selection

- **Fixed window** increments a counter atomically and resets it after `windowSeconds`. It is simple and inexpensive, but allows bursts at adjacent window boundaries.
- **Token bucket** starts with `burstCapacity` tokens and replenishes at `sustainedLimit / windowSeconds`. It permits short bursts while smoothing sustained traffic.
- When no explicit tenant/resource rule exists, the service uses a token bucket with sustained limit 10 per 60 seconds and burst capacity 20.

## Shared-state correctness and failure behavior

Each app replica reads the active rule from Redis and invokes a Redis Lua script for the counter/token update. Redis executes each script atomically, bounding the race window to a single serialized script operation. The concurrent integration test submits requests through the HTTP layer and asserts that no more than the configured capacity is allowed.

Redis is required for rule storage and quota evaluation. The service fails closed when Redis is unavailable: it returns `503` with `REDIS_UNAVAILABLE` rather than making an allow decision from local state. Readiness includes Redis health; liveness only reports process health.

## Deployment and known limitations

The deployment Compose file runs two Spring Boot containers, Redis with AOF, and HAProxy. The app containers share Redis; the proxy binds to `127.0.0.1:8080`. This demonstrates cross-replica correctness, but all services on a single Droplet remain a single-host failure domain. Redis replication/failover, backup restore, TLS, API authentication/authorization, and production access controls are not configured. Do not expose the current API publicly without adding those controls.

See [docs/architecture.md](docs/architecture.md) for request flow and rule/evaluation lifecycle. GitHub Actions starts Redis, runs tests, and packages the application on pushes and pull requests.

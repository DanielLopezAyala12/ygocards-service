# ygocards-service

A small HTTP service over the public [YGOPRODeck](https://ygoprodeck.com/api-guide/) card API,
written as a twelve-factor application for Software Architecture 4 at Jala University.

Java 21, Spring Boot 4.1, Maven Wrapper. The service exports HTTP on a port it reads from the
environment, keeps no durable state, and treats the upstream API as an attached resource whose
address is configuration rather than code.

**Status: in progress.** Card search, image serving, the frontend and the container image are
not implemented yet. What runs today is the configuration layer and the two health endpoints.

---

## Quick start

No configuration is required. Every setting has a default that works on a clean machine.

```bash
./mvnw -B -DskipTests package
java -jar target/ygocards-service.jar
```

Then, in another terminal:

```bash
curl http://localhost:8080/health
curl http://localhost:8080/health/upstream
```

### With the start and stop scripts

The scripts add two things the bare command does not: they load `.env` into the environment,
and they stop the process in a way that actually runs its graceful shutdown.

```bash
cp .env.example .env          # optional, the defaults work without it

scripts/start.sh              # Linux, macOS, WSL
scripts/stop.sh

powershell -File scripts\start.ps1    # Windows
powershell -File scripts\stop.ps1
```

There are two versions of each script because stopping the process is not the same operation on
both platforms. On Linux the stop script sends SIGTERM, which is what a container runtime sends.
Windows has no SIGTERM, so the PowerShell version delivers a Ctrl+C console event instead, which
is the only Windows mechanism that runs the JVM shutdown hooks. `Stop-Process` and `taskkill /F`
end the process without running them, which skips the graceful drain entirely. The reasoning is
written out at the top of each script.

There is also a `Procfile` for platforms that read one. It declares the single process type this
application has:

```
web: java -jar target/ygocards-service.jar
```

A `Procfile` does not load `.env` by itself. Either use the start scripts, export the variables
some other way, or pass `--env-file .env` to `docker run` once the image exists.

---

## Endpoints

| Method and path | Purpose |
|---|---|
| `GET /health` | Liveness. Is this process alive and serving HTTP |
| `GET /health/upstream` | Reachability of the card API, from a probe result that may be cached |

### Why these are two endpoints and not one

`/health` never calls the upstream API. If it did, the service would fail its own health check
whenever the upstream had a problem, and an orchestrator would restart a process that is working
correctly. That is worse than useless here: the provider blocks a caller for an hour after too
many requests, and restarting the process also discards the cache, which increases the number of
upstream calls once the block expires. Mixing the two questions would turn a provider problem
into an outage and then make the provider problem worse.

```json
GET /health  ->  200
{ "status": "UP", "version": "0.1.0", "uptimeSeconds": 8 }
```

`/health/upstream` answers the other question, and returns 503 when the answer is no, so that a
caller deciding whether to send traffic here sees a failure rather than a field buried inside a
200 response.

```json
GET /health/upstream  ->  200 or 503
{
  "status": "UP",
  "upstream": {
    "reachable": true,
    "latencyMs": 657,
    "checkedAt": "2026-09-26T20:53:20.712879500Z",
    "ageSeconds": 0,
    "source": "probe",
    "detail": "reachable"
  }
}
```

The probe result is cached for `UPSTREAM_PROBE_TTL`. Without that, a monitor polling once a
second would spend the entire outbound request budget on health checks and trigger the block the
endpoint exists to detect. `source` and `ageSeconds` are in the response so that a cached answer
is never mistaken for a live one.

Spring Boot Actuator offers `/actuator/health` with liveness and readiness groups and would be
the sensible choice in production. These endpoints are hand written because the assignment asks
for `/health` specifically, and because writing them keeps the health logic attributable to this
project rather than to a framework default.

---

## Configuration

Every value is read from an environment variable with a fallback, in the form
`${VARIABLE:default}` in `src/main/resources/application.properties`. No code reads a variable
directly and no deployment needs a different build.

| Variable | Default | What it controls |
|---|---|---|
| `PORT` | `8080` | Port the embedded server binds |
| `SHUTDOWN_TIMEOUT` | `20s` | Grace period for in-flight requests on shutdown |
| `LOG_LEVEL` | `INFO` | Root log level |
| `APP_LOG_LEVEL` | `INFO` | Log level for this application's packages |
| `YGO_API_BASE_URL` | `https://db.ygoprodeck.com/api/v7` | Card API root |
| `YGO_IMAGE_BASE_URL` | `https://images.ygoprodeck.com/images` | Card image root, used server side only |
| `YGO_PROBE_PATH` | `/checkDBVer.php` | Endpoint used by the upstream health probe |
| `UPSTREAM_PROBE_TTL` | `30s` | How long a probe result stays usable |
| `YGO_RATE_LIMIT_PER_SECOND` | `8` | Outbound request ceiling |
| `YGO_RATE_LIMIT_WAIT` | `500ms` | How long a request waits for a permit before 503 |
| `YGO_CONNECT_TIMEOUT` | `3s` | Connect timeout for upstream calls |
| `YGO_READ_TIMEOUT` | `5s` | Read timeout for upstream calls |
| `CACHE_CARD_TTL` | `15m` | Card cache entry lifetime |
| `CACHE_CARD_MAX_ENTRIES` | `500` | Card cache entry ceiling |
| `CACHE_IMAGE_TTL` | `1h` | Image cache entry lifetime |
| `CACHE_IMAGE_MAX_ENTRIES` | `200` | Image cache entry ceiling |

`.env.example` lists the same variables with the same defaults and explains which tools read it.
Copy it to `.env` if you want to change something. `.env` is git-ignored; the template is not.

Neither file holds a secret. That is a property of the upstream API, which needs no credential,
not a claim about this design. If one is ever added it belongs in the environment, and it must
not appear in the configuration block the service prints at startup.

### The rate limit default is not the rate limit

The provider allows 20 requests per second and blocks the caller for an hour beyond that. The
default here is 8. The margin is cheap and the penalty is not.

Worth stating plainly: this limiter counts requests **per instance**. Three replicas at 8 per
second make 24 and would trigger the block. Sharing a limit across replicas needs an external
coordinator, which is recorded as a deferred improvement rather than claimed as solved.

---

## Twelve-factor mapping

The canonical mapping table, naming for each factor whether it comes from a framework default,
from an explicit setting, or from code written for this project, is added here when the service
is feature complete. The lab report quotes this table rather than maintaining a second copy.

---

## Licence and attribution

Card data and card images come from the YGOPRODeck API and remain the property of their
respective owners. The service caches upstream data locally and re-serves images through its own
endpoint, as the provider's usage guidelines ask, and never points a browser at the upstream
image host.

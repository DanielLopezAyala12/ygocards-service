# ygocards-service

A small HTTP service over the public [YGOPRODeck](https://ygoprodeck.com/api-guide/) card API,
written as a twelve-factor application for Software Architecture 4 at Jala University.

Java 21, Spring Boot 4.1, Maven Wrapper. The service exports HTTP on a port it reads from the
environment, keeps no durable state, and treats the upstream API as an attached resource whose
address is configuration rather than code.

**Status: in progress.** The backend, the frontend and the container image are complete. The
tunnel deployment and the public mirror are not done yet.

---

## Quick start

No configuration is required. Every setting has a default that works on a clean machine.

### With Docker

```bash
docker build -t ygocards-service:0.1.0 .
docker run --rm --env-file .env -p 8080:8080 ygocards-service:0.1.0
```

`docker stop` sends a real SIGTERM to the JVM, which is process 1 in the container, so the
graceful drain runs and the container exits with code 143. That is the cleanest demonstration of
factor IX available here, because the signal is native rather than emulated.

The admin task runs from the same image:

```bash
docker run --rm --env-file .env ygocards-service:0.1.0 --admin=upstream-check
```

### Without Docker

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
| `GET /api/cards?name=&limit=&offset=` | Fuzzy card search by name |
| `GET /api/cards/{id}/image?variant=small\|full` | One card image, served from here |

### Card search

```bash
curl "http://localhost:8080/api/cards?name=dark%20magician&limit=2"
```

```json
{
  "query": { "name": "dark magician", "limit": 2, "offset": 0 },
  "count": 2,
  "totalRows": 15,
  "cache": "miss",
  "cards": [
    {
      "id": 46986414,
      "name": "Dark Magician",
      "type": "Normal Monster",
      "atk": 2500,
      "def": 2100,
      "level": 7,
      "image": {
        "small": "/api/cards/46986414/image?variant=small",
        "full": "/api/cards/46986414/image?variant=full"
      }
    }
  ]
}
```

`limit` defaults to 20 and may not exceed 100. `offset` defaults to 0. Responses also carry an
`X-Cache` header of `HIT` or `MISS`, which is the same information as the `cache` field in a form
a proxy or a test can read without parsing the body.

A query that matches nothing returns **200 with an empty list**, not 404 and not the 400 the
upstream answers with. Finding nothing is a successful search, and translating it here keeps the
provider's contract from leaking into every client.

### Card images

Image links in a search response always point back at this service. No response this service
produces contains the upstream image host, because the provider's guidelines prohibit hotlinking
it. The upstream URL is rebuilt from `YGO_IMAGE_BASE_URL` and the card id on each request rather
than remembered from a search: an image endpoint that depended on a previous search would depend
on the cache, and a cold instance would fail to serve an image it had never looked up.

Images are served from a bounded in-memory cache and never written to disk. Durable image storage
belongs in object storage reached through the environment, and is recorded as a deferred factor.

### Error semantics

Four different failures deserve four different answers, because the right reaction differs:

| Status | Meaning | What a caller should do |
|---|---|---|
| `400` | The request is wrong | Fix it. Retrying unchanged will fail again |
| `502` | The card API could not be reached or answered unusably | Nothing is wrong on either side of this call |
| `504` | The card API accepted the call and did not answer in time | Often clears by itself, unlike 502 |
| `503` | This service declined to call out, its own ceiling was reached | Wait. `Retry-After` says how long |

The 503 case is worth separating from the rest: nothing is broken anywhere. The service is
holding outbound traffic under the provider's limit on purpose, and saying so is more useful than
reporting an upstream problem that does not exist.

```json
{
  "error": "rate_limited",
  "message": "This service is holding outbound traffic under the provider's limit. Try again shortly.",
  "retryAfterSeconds": 1
}
```

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

## Frontend

A static page served by this service from `src/main/resources/static`, at `/`. It searches by
name, shows results as cards with image, name, type, attribute, level, ATK and DEF where they
apply, and the card text.

Card images are taken from the `image` object in the API response, which always points back at
this service. The browser never learns the upstream image host exists.

The page also reports whether the answer came from this service's cache or from a call to the
provider, read from the `X-Cache` response header. That is a deliberate choice rather than
decoration: the caching behaviour is the part of this design most worth seeing, and a chip that
changes from "Fetched from the card API" to "Served from this service's cache" on the second
identical search demonstrates it better than any amount of prose.

The four error statuses are presented differently, and the 503 is not presented as a failure.
An upstream problem is shown in the error colour; a rate limit refusal is shown in a neutral
colour with the wait time, because nothing is broken and telling the user the provider is down
would be false.

### Material Design 3 without a build chain

Implemented directly against the Material Design 3 specification in one stylesheet: the colour
role system in light and dark, the type scale, the shape scale, elevation levels and state
layers. No component library is loaded.

That was not the first choice. The intent was to vendor the published Material Web bundle at a
pinned version, which would have kept a declared dependency without adding npm. The bundle turns
out not to be self contained: the distributed build still imports `lit` and `tslib` from absolute
content delivery network paths, so vendoring the file would have left the browser fetching
dependencies at run time anyway. Since the point of vendoring was to remove exactly that, and
rewriting the import graph by hand would be building a second build chain, the tokens were
implemented directly instead.

The result is stronger for factor II than either option considered: the page loads **no script,
stylesheet or font from anywhere but this service**, so its run-time dependency count is zero and
there is nothing left to declare. MD3 specifies Roboto and a system font stack is used instead,
for the same reason.

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

This is the canonical table. The lab report quotes it rather than keeping a second copy that
could drift.

The **Source** column exists because a framework can satisfy several factors without anyone
having decided anything, and taking credit for that would be dishonest. It has three values:

- **Framework** the behaviour comes from Spring Boot and would be there whether or not anyone
  thought about it.
- **Configured** a framework capability that does nothing until someone turns it on, and someone
  did.
- **Written** code or files produced for this project.

| # | Factor | Mechanism and where to find it | Source |
|---|---|---|---|
| I | Codebase | One Git repository. The source lives in the coursework repository under this lab folder; a public mirror carries the same code and supplies the public URL. Reasoning in `architecture-decisions.md`, D-06 | Configured |
| II | Dependencies | Java dependencies declared in `pom.xml`. The build tool itself pinned to 3.9.16 by the Maven Wrapper (`.mvn/wrapper/maven-wrapper.properties`). Base images pinned to exact tags in `Dockerfile`. The frontend loads **no script, stylesheet or font from any other origin**, so its run-time dependency count is zero | Wrapper is **Framework**; the manifest, the pinned tags and the zero-dependency frontend are **Written** |
| III | Config | Every setting is `${VAR:default}` in `application.properties`, bound into records in `config/UpstreamProperties.java` and `config/CacheProperties.java`. No class calls `System.getenv` anywhere. `.env.example` documents the full surface. `observability/StartupLogger.java` prints the resolved values at startup | Binding is **Framework**; the discipline of zero direct reads, and the startup report, are **Written** |
| IV | Backing services | The card API is reached through `YGO_API_BASE_URL` and `YGO_IMAGE_BASE_URL`, never a compiled address. The tests point the application at a fake upstream using the same property a deployment would use to select a mirror (`src/test/java/edu/jala/ygocards/FakeUpstream.java`) | Written |
| V | Build, release, run | Two-stage `Dockerfile`: the build stage holds the JDK, the wrapper and the sources; the runtime stage holds a JRE and one jar. Image tagged `ygocards-service:0.1.0`. The running version comes from `app.version=@project.version@`, filtered from `pom.xml` at build time, so there is one source of truth for it | Configured |
| VI | Processes | No session state, no writes to disk. Both caches are bounded by entries and by time to live (`application/CardSearchService.java`, `application/CardImageService.java`). Images are held in memory and re-served, never written (`architecture-decisions.md`, D-05) | Written |
| VII | Port binding | Spring Boot's embedded Tomcat exports HTTP with no external server. The only contribution here is one line, `server.port=${PORT:8080}`, so that the port is a deployment decision rather than a constant | Embedded server is **Framework**; reading `PORT` is **Configured** |
| VIII | Concurrency | One process type, declared in `Procfile` as `web:`. Capacity is added by running more containers. No singleton work and no scheduled jobs, so nothing breaks when a second instance starts. See the limitations below for what does not scale yet | Written |
| IX | Disposability | `server.shutdown=graceful` with `spring.lifecycle.timeout-per-shutdown-phase`. `observability/ShutdownLogger.java` makes the drain visible, since the framework prints nothing by itself. The `Dockerfile` uses exec-form `ENTRYPOINT` so the JVM is PID 1 and receives SIGTERM directly. Verified: `docker stop` produced the full drain sequence and exit code 143 | Graceful drain is **Configured**; the log line and the PID 1 arrangement are **Written** |
| X | Dev/prod parity | The same image runs locally and anywhere else, built from the same `Dockerfile` with the same variable names. Tests exercise the real configuration path rather than a test-only one. The only environment difference is the value of the variables | Configured |
| XI | Logs | Spring Boot writes to stdout by default, and this project adds **no file appender anywhere**. The contribution is negative and deliberate: not breaking it. What was written is the content, namely cache hits and misses, upstream latency per call, rate limiter decisions and the startup configuration block | stdout is **Framework**; the absence of a file appender is **Configured**; the log content is **Written** |
| XII | Admin processes | `admin/AdminRunner.java` handles `--admin=upstream-check` from the same jar with the same configuration, starts no web server, and exits 0, 2 or 3. Runs as `docker run --rm --env-file .env ygocards-service:0.1.0 --admin=upstream-check` | Written |

### Known limitation: request amplification against a shared ceiling

One user action is not one outbound call. A search is one call, but the page then requests an
image per card, so a single search with 24 results can produce up to 25 outbound calls against a
ceiling of 8 per second. Measured with a cold cache: a burst of 24 image requests produced 8
refusals in one run and 4 in another, while a burst of 15 produced none. The same action fails a
different number of images depending on where the burst lands relative to the refill tick.

The ceiling protects the provider, which is what it was for, but it distributes the cost onto the
user unpredictably. The fix is a design decision rather than a tuning change and is still open;
it is recorded here rather than left for a reader to discover.

### Deferred, with reasons

| What | Factor | Why it is not here |
|---|---|---|
| Durable image storage in object storage | IV, VI | The correct answer is an attached resource reached through a URL in the environment. Writing images to local disk would satisfy the provider and break factor VI, so they are held in memory instead and persistence is out of scope (`architecture-decisions.md`, D-05) |
| Shared cache and shared outbound budget across replicas | IV, VIII | Both the cache and the rate limiter count per instance. Three replicas at eight requests per second would send twenty four and trigger the provider's block. Sharing either needs an external coordinator |
| Named tunnel with a stable hostname | VII | The quick tunnel produces an address that disappears with the process, which is accepted in `architecture-decisions.md`, D-03. A named tunnel is the production form of the same arrangement |
| A ceiling that accounts for amplification | VIII | See the limitation above. Open |

---

## Licence and attribution

Card data and card images come from the YGOPRODeck API and remain the property of their
respective owners. The service caches upstream data locally and re-serves images through its own
endpoint, as the provider's usage guidelines ask, and never points a browser at the upstream
image host.

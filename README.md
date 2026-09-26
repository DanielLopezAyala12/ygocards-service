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
| `YGO_RATE_LIMIT_SEARCH_PER_SECOND` | `3` | Outbound ceiling for card searches |
| `YGO_RATE_LIMIT_SEARCH_WAIT` | `750ms` | How long a search waits for a permit before 503 |
| `YGO_RATE_LIMIT_IMAGE_PER_SECOND` | `9` | Outbound ceiling for card images |
| `YGO_RATE_LIMIT_IMAGE_WAIT` | `4s` | How long an image waits for a permit before 503 |
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

### Two outbound budgets, and why not one

The provider allows 20 requests per second and blocks the caller for an hour beyond that. This
service spends at most 12: 3 on searches and 9 on images. The margin is cheap and the penalty is
not.

The split matters more than the numbers. A search and an image are not the same class of traffic.
One search produces one upstream call, and the page it returns then requests one image per card,
so they stand in a ratio of about one to twenty four. A single shared counter guarantees that the
abundant class crowds out the scarce one, and measurement confirmed it: under one shared ceiling
every refusal in the log was an image and none was a search. That was luck rather than design.
With a different arrival order the user would have lost the search they asked for so that images
could load.

They also deserve different patience, for a reason that has nothing to do with volume:

| | Search | Image |
|---|---|---|
| Who asked for it | A person, directly | Derived from their search |
| Are they watching this one | Yes | Not any particular one |
| Cost of failure | The whole action | One card out of twenty four |
| Retryable and cacheable | Re-runs the query | Idempotent, cached an hour |
| Budget and patience | 3 per second, wait 750ms | 9 per second, wait 4s |

A search gets a small budget that is reliably there and a short wait, because a spinner that
hangs is worse than an honest failure. An image can afford to queue.

### The limiter refills continuously, not on a tick

An earlier version released a batch of permits once a second while a caller waited at most half a
second, so whether a waiting request was served depended on where the burst landed relative to
that tick. Measured, the same page of 24 images was refused 8 times in one run and 4 in the next.
That is a coin flip, and a system that fails at random is harder to operate than one that fails
predictably: it cannot be reproduced, it cannot be tuned against, and the same action succeeding
one day and failing the next teaches an operator nothing.

`TokenBucket` computes the permit count as a function of elapsed time at the moment it is asked
for. A caller that cannot be served now is told exactly how long its turn is away, and that
answer is the same every time for the same position in the queue.

Measured before and after, with cold caches, bursting every image in a result page:

| Burst | Shared ceiling of 8, tick refill | Split budgets, continuous refill |
|---|---|---|
| 15 images | 15 succeeded | 15 succeeded |
| 24 images, run 1 | 16 succeeded, **8 refused** | 24 succeeded, 2020 ms |
| 24 images, run 2 | 20 succeeded, **4 refused** | 24 succeeded, 2010 ms |
| 24 images, run 3 | not run | 24 succeeded, 2006 ms |

Three runs finishing within 14 ms of each other is the point. The delays the limiter applied form
a ladder roughly 111 ms apart, which is one ninth of a second at 9 permits per second: the burst
became an orderly queue instead of a scramble, and nothing was refused.

### Still per instance

Both budgets count inside one process. There are now two limits that are not shared between
replicas rather than one. Two replicas at 3 and 9 would together send 24, over the provider's
ceiling. Sharing either needs an external coordinator, and that remains deferred rather than
solved.

---

## Twelve-factor mapping

This is the canonical table. The lab report quotes it rather than keeping a second copy that
could drift.

The **Source** column exists because tooling can satisfy several factors without anyone having
decided anything, and taking credit for that would be dishonest. It has three values:

- **Framework** behaviour that exists without anyone deciding anything, whether it comes from
  Spring Boot or from the surrounding tooling.
- **Configured** a capability that already existed but does nothing until someone turns it on or
  points it somewhere, and someone did.
- **Written** code, files or arrangements produced for this project.

Two of those definitions are wider than they first look, deliberately. **Framework** is not
"Spring Boot": the Maven Wrapper pins the build tool without anyone here having designed that,
and it belongs in the same category for the same reason. **Written** is not only Java: a
`Dockerfile` is a file written for this project, and classifying it as configuration would
understate it.

| # | Factor | Mechanism and where to find it | Source |
|---|---|---|---|
| I | Codebase | One Git repository. The source lives in the coursework repository under this lab folder; a public mirror carries the same code and supplies the public URL. A mirror is a second remote of one codebase, not a second codebase. Reasoning in `architecture-decisions.md`, D-06. This is an arrangement of remotes rather than a file or a setting, so the label is the closest of the three rather than a strong claim | Configured |
| II | Dependencies | Java dependencies declared in `pom.xml`. The build tool itself pinned to 3.9.16 by the Maven Wrapper (`.mvn/wrapper/maven-wrapper.properties`). Base images pinned to exact tags in `Dockerfile`. The frontend loads **no script, stylesheet or font from any other origin**, so its run-time dependency count is zero | Wrapper is **Framework**; the manifest, the pinned tags and the zero-dependency frontend are **Written** |
| III | Config | Every setting is `${VAR:default}` in `application.properties`, bound into records in `config/UpstreamProperties.java` and `config/CacheProperties.java`. No class calls `System.getenv` anywhere. `.env.example` documents the full surface. `observability/StartupLogger.java` prints the resolved values at startup | Binding is **Framework**; the discipline of zero direct reads, and the startup report, are **Written** |
| IV | Backing services | The card API is reached through `YGO_API_BASE_URL` and `YGO_IMAGE_BASE_URL`, never a compiled address. The tests point the application at a fake upstream using the same property a deployment would use to select a mirror (`src/test/java/edu/jala/ygocards/FakeUpstream.java`) | Written |
| V | Build, release, run | Three separable stages, not two. **Build**: the `Dockerfile` build stage turns sources into one jar using the JDK and the wrapper; the runtime stage keeps only the jar, so nothing needed to produce the artefact can influence what runs. **Release**: the image plus one environment, as `docker run --env-file <environment>`. It has an identity, `ygocards-service:0.1.0` together with the file that supplied its variables, and it is immutable in the way the factor means: no variable can be changed in a running container, so a configuration change produces a new container from the same image rather than mutating the old one. **Run**: the container executes that release and changes nothing about it. The running version comes from `app.version=@project.version@`, filtered from `pom.xml` at build time, so build identity has one source of truth. See the note below on where this is weakest | Written |
| VI | Processes | No session state, no writes to disk. Both caches are bounded by entries and by time to live (`application/CardSearchService.java`, `application/CardImageService.java`). Images are held in memory and re-served, never written (`architecture-decisions.md`, D-05) | Written |
| VII | Port binding | Spring Boot's embedded Tomcat exports HTTP with no external server. The only contribution here is one line, `server.port=${PORT:8080}`, so that the port is a deployment decision rather than a constant | Embedded server is **Framework**; reading `PORT` is **Configured** |
| VIII | Concurrency | One process type, declared in `Procfile` as `web:`. Capacity is added by running more containers. No singleton work and no scheduled jobs, so nothing breaks when a second instance starts. Outbound traffic is split into two budgets over a deterministic token bucket (`upstream/TokenBucket.java`, `upstream/UpstreamRateLimiter.java`), because a search and an image are different classes of traffic standing in a ratio of one to twenty four. Both budgets still count per instance | Written |
| IX | Disposability | `server.shutdown=graceful` with `spring.lifecycle.timeout-per-shutdown-phase`. `observability/ShutdownLogger.java` makes the drain visible, since the framework prints nothing by itself. The `Dockerfile` uses exec-form `ENTRYPOINT` so the JVM is PID 1 and receives SIGTERM directly. Verified: `docker stop` produced the full drain sequence and exit code 143 | Graceful drain is **Configured**; the log line and the PID 1 arrangement are **Written** |
| X | Dev/prod parity | The factor names three gaps and all three are addressed below: **time** between writing and deploying, **personnel** between who writes and who deploys, and **tools** between what each environment runs. The tools gap is the one with real content here: the same image runs everywhere, built from the same `Dockerfile`, with the same variable names, and the tests exercise the real configuration path rather than a test-only one. The only difference between environments is the value of the variables | Written |
| XI | Logs | Spring Boot writes to stdout by default, and this project adds **no file appender anywhere**. The contribution is negative and deliberate: not breaking it. What was written is the content, namely cache hits and misses, upstream latency per call, rate limiter decisions and the startup configuration block | stdout is **Framework**; the absence of a file appender is **Configured**; the log content is **Written** |
| XII | Admin processes | `admin/AdminRunner.java` handles `--admin=upstream-check` from the same jar with the same configuration, starts no web server, and exits 0, 2 or 3. Runs as `docker run --rm --env-file .env ygocards-service:0.1.0 --admin=upstream-check` | Written |

### Notes on two factors that deserve more than a table cell

**Factor V: release is the weakest of the three stages.** Build and run are cleanly separated by
the image. Release is present but thin. A release here is an image tag plus an environment file,
and nothing records which pair was deployed when: there is no release identifier that a person
could quote in an incident, no store of past releases, and therefore no way to roll back to a
known configuration rather than to a known image. The immutability the factor asks for does hold,
because a running container cannot have its environment changed and a configuration change forces
a new one. What is missing is the bookkeeping, and the honest statement is that this project has
build and run properly separated with release as the thinnest of the three.

**Factor X: two of the three gaps are trivial here, and saying why is the point.**

- **Time.** Minutes. The same person writes, builds the image and deploys it in one session, so
  the gap the factor worries about, code sitting unshipped for weeks, cannot form. This is trivial
  because the project is small, not because anything was designed to prevent it.
- **Personnel.** Zero. One person writes and deploys. The failure mode the factor describes, a
  developer who never operates what they wrote, is absent by accident of scale rather than by
  design, and it would reappear the moment a second person joined.
- **Tools.** This is the one that took work. The same `Dockerfile` produces the artefact for every
  environment, the variable names are identical everywhere, and the tests drive the real
  configuration path. There is no database, so the classic version of this gap, SQLite locally and
  PostgreSQL in production, cannot occur here at all.

Stating that two of the three are trivial is more useful than omitting them, because it separates
what the project earned from what it got for free by being small.

### Two defects found by looking at the system whole

Neither of these is visible in any single component. Both appeared only when one user action was
followed from the click to the last outbound call, and both are written up in the configuration
section above.

**Displacement.** One search is one upstream call, but the page then requests an image per card,
so one action can produce 25 calls. Under a single shared ceiling the abundant class consumed the
budget and the scarce one survived by accident. Fixed by giving searches and images separate
budgets with separate patience.

**Unpredictability.** Permits were released on a fixed one second tick while callers waited half
a second, so success depended on where the burst landed relative to the clock. Fixed by
continuous refill, which turns a coin flip into a queue.

The general lesson, which belongs in the report rather than here: a ceiling that ignores
amplification protects the provider and charges the user unpredictably, and it charges the
traffic class that was requested rather than the one that is abundant.

### Deferred, with reasons

| What | Factor | Why it is not here |
|---|---|---|
| Durable image storage in object storage | IV, VI | The correct answer is an attached resource reached through a URL in the environment. Writing images to local disk would satisfy the provider and break factor VI, so they are held in memory instead and persistence is out of scope (`architecture-decisions.md`, D-05) |
| Shared cache and shared outbound budgets across replicas | IV, VIII | Caches and both rate limit budgets count per instance. Two replicas at three searches and nine images per second would together send twenty four, over the provider's ceiling of twenty. Splitting the budget by traffic class fixed displacement inside one process; it did nothing for scaling out, which needs an external coordinator |
| Named tunnel with a stable hostname | VII | The quick tunnel produces an address that disappears with the process, which is accepted in `architecture-decisions.md`, D-03. A named tunnel is the production form of the same arrangement |
| Byte-aware bound on the image cache | VI | The image cache is bounded by entry count, so its memory ceiling is approximate: 200 entries at roughly 150 KB is about 30 MB. A weigher would be stricter if that limit ever mattered |

---

## Licence and attribution

The source code of this service is MIT licensed. See [`LICENSE`](./LICENSE).

**The licence covers the code and nothing else.** Card data and card images come from the
YGOPRODeck API and remain the property of YGOPRODeck and of the rights holders of the Yu-Gi-Oh
trading card game. They are not the author's to license, and the `LICENSE` file says so in its
own words rather than leaving the question to be inferred. A licence that appeared to cover
somebody else's data would be worse than no licence at all.

The service caches upstream data locally and re-serves images through its own endpoint, as the
provider's usage guidelines ask, and never points a browser at the upstream image host.

## A note on where the mapping table lives

The table above is the canonical copy. The lab report at
[`../README.md`](../README.md) carries a duplicate of it, deliberately rather than by oversight:
the mapping is the largest single item in the lab's marking scheme, and the cost of an assessor
not finding it outweighs the risk of the two copies drifting apart now that the code is frozen.
If the service changes again, this copy is the one to edit first.

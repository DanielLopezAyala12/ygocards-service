package edu.jala.ygocards.web;

import edu.jala.ygocards.upstream.UpstreamProbe;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two health endpoints that answer two different questions.
 *
 * <p>{@code /health} answers "is this process alive and serving HTTP". It never calls the
 * upstream API. If it did, an upstream rate-limit block, which lasts an hour under the
 * provider's contract, would make an orchestrator restart a process that is working perfectly.
 * The restart would also discard the cache, which increases the number of upstream calls once
 * the block expires, so mixing the two questions does not just misreport the problem, it makes
 * the problem worse.
 *
 * <p>{@code /health/upstream} answers "can this process reach the card API". It returns 503
 * when the upstream is unreachable, because a caller deciding whether to send traffic here
 * needs that to be a failure rather than a field buried in a 200 response.
 */
@RestController
public class HealthController {

    private final Instant startedAt = Instant.now();
    private final UpstreamProbe upstreamProbe;
    private final String version;

    public HealthController(UpstreamProbe upstreamProbe, @Value("${app.version}") String version) {
        this.upstreamProbe = upstreamProbe;
        this.version = version;
    }

    /** Liveness. Answers from inside this process only. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("version", version);
        body.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds());
        return body;
    }

    /** Reachability of the backing service, from a probe result that may be cached. */
    @GetMapping("/health/upstream")
    public ResponseEntity<Map<String, Object>> upstream() {
        UpstreamProbe.Outcome outcome = upstreamProbe.current();
        UpstreamProbe.Result result = outcome.result();

        Map<String, Object> upstream = new LinkedHashMap<>();
        upstream.put("reachable", result.reachable());
        upstream.put("latencyMs", result.latencyMs());
        upstream.put("checkedAt", result.checkedAt().toString());
        upstream.put("ageSeconds", result.ageSeconds());
        upstream.put("source", outcome.source().name().toLowerCase());
        upstream.put("detail", result.detail());
        // Free with the probe response, and the cheapest way to notice that the upstream data
        // set changed underneath a cache that is still inside its time to live.
        upstream.put("databaseVersion", result.databaseVersion());
        upstream.put("databaseUpdatedAt", result.databaseUpdatedAt());

        // Three states, not two. "Not checked" is not the same claim as "checked and down", and
        // reporting the first as the second would be a guess presented as a measurement.
        String status;
        if (outcome.source() == UpstreamProbe.Source.UNKNOWN) {
            status = "UNKNOWN";
        } else {
            status = result.reachable() ? "UP" : "DOWN";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("upstream", upstream);

        HttpStatus httpStatus = "UP".equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(body);
    }
}

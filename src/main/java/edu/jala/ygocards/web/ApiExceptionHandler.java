package edu.jala.ygocards.web;

import edu.jala.ygocards.upstream.RateLimitedException;
import edu.jala.ygocards.upstream.UpstreamTimeoutException;
import edu.jala.ygocards.upstream.UpstreamUnavailableException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * One place that decides what a caller sees when something goes wrong.
 *
 * <p>The status codes distinguish four different situations that would otherwise all look like
 * "the service is broken", because the right reaction differs in each case:
 *
 * <ul>
 *   <li><b>400</b> the request itself is wrong. Retrying it unchanged will fail again.</li>
 *   <li><b>502</b> the upstream could not be reached or answered with something unusable. The
 *       fault is outside this service and outside the caller.</li>
 *   <li><b>504</b> the upstream accepted the call and did not answer in time. Separated from 502
 *       because a timeout often clears on its own while a hard failure usually does not.</li>
 *   <li><b>503</b> this service declined to make the call, because its own outbound ceiling was
 *       reached. Nothing is wrong anywhere; the caller should wait, and {@code Retry-After} says
 *       for how long.</li>
 * </ul>
 *
 * <p>A search that matches no card is not in this list. It is a successful request with an empty
 * result and it returns 200.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> onInvalidRequest(InvalidRequestException e) {
        return ResponseEntity.badRequest().body(body("invalid_request", e.getMessage()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> onBadParameter(Exception e) {
        return ResponseEntity.badRequest().body(body("invalid_request", e.getMessage()));
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<Map<String, Object>> onRateLimited(RateLimitedException e) {
        log.warn("Refusing request: outbound rate limit reached, advising retry in {}s",
                e.retryAfterSeconds());
        Map<String, Object> body = body("rate_limited",
                "This service is holding outbound traffic under the provider's limit. Try again shortly.");
        body.put("retryAfterSeconds", e.retryAfterSeconds());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(body);
    }

    @ExceptionHandler(UpstreamTimeoutException.class)
    public ResponseEntity<Map<String, Object>> onTimeout(UpstreamTimeoutException e) {
        log.warn("Upstream timed out: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(body("upstream_timeout", "The card API did not answer in time."));
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    public ResponseEntity<Map<String, Object>> onUnavailable(UpstreamUnavailableException e) {
        log.warn("Upstream unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(body("upstream_unavailable", "The card API could not be reached."));
    }

    private static Map<String, Object> body(String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        return body;
    }
}

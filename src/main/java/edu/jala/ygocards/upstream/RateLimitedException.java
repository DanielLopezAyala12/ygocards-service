package edu.jala.ygocards.upstream;

/**
 * This service refused to make an outbound call because its own ceiling was reached.
 *
 * <p>Worth separating from an upstream failure: nothing is wrong with the provider, and the
 * caller can simply try again shortly. The response says so with 503 and {@code Retry-After}
 * rather than pretending the upstream is down.
 */
public class RateLimitedException extends UpstreamException {

    private final long retryAfterSeconds;

    public RateLimitedException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

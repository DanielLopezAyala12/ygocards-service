package edu.jala.ygocards.upstream;

/** The upstream accepted the connection but did not answer inside the configured budget. */
public class UpstreamTimeoutException extends UpstreamException {

    public UpstreamTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}

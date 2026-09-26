package edu.jala.ygocards.upstream;

/** Base for every failure that originates outside this process. */
public abstract class UpstreamException extends RuntimeException {

    protected UpstreamException(String message, Throwable cause) {
        super(message, cause);
    }

    protected UpstreamException(String message) {
        super(message);
    }
}

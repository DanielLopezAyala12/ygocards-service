package edu.jala.ygocards.upstream;

/** The upstream could not be reached, or answered with something this service cannot use. */
public class UpstreamUnavailableException extends UpstreamException {

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

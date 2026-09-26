package edu.jala.ygocards.web;

/** A request this service rejects before any outbound call is made. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}

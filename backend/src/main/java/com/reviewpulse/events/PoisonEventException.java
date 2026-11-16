package com.reviewpulse.events;

/**
 * An event that can never be applied (undecodable, wrong schema, invalid field values).
 * Retrying cannot help, so it goes to the dead-letter topic instead of blocking the partition.
 */
public class PoisonEventException extends RuntimeException {

    public PoisonEventException(String message) {
        super(message);
    }

    public PoisonEventException(String message, Throwable cause) {
        super(message, cause);
    }
}

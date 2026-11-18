package com.loofie.core.common;

/**
 * A service we depend on (file storage, Backend A) is down or not configured here. The client gets 503 with this
 * message and a Retry-After; the rest of the app keeps working.
 */
public class DependencyUnavailableException extends RuntimeException {

    public DependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

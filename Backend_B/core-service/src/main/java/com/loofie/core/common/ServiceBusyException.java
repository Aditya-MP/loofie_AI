package com.loofie.core.common;

/** A protective limit was hit (e.g. too many slow reads at once). The client should retry shortly: 503. */
public class ServiceBusyException extends RuntimeException {

    public ServiceBusyException(String message) {
        super(message);
    }
}

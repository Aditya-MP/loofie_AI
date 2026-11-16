package com.loofie.core.refund.domain;

/** A refund rule was not met (answered 422). */
public class RefundRuleException extends RuntimeException {
    public RefundRuleException(String message) {
        super(message);
    }
}

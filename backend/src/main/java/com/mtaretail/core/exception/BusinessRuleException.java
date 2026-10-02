package com.mtaretail.core.exception;

/** Request violates a business rule. Mapped to HTTP 422. */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
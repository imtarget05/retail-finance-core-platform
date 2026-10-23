package com.mtaretail.core.exception;

/** A previously used idempotency key cannot describe a different movement. */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("Idempotency key was already used for a different transaction");
    }
}

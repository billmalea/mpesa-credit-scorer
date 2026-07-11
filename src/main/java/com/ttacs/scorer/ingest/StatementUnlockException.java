package com.ttacs.scorer.ingest;

public class StatementUnlockException extends RuntimeException {

    public StatementUnlockException(String message) {
        super(message);
    }

    public StatementUnlockException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.divakar.opspilot.agent;

/** The AI pipeline failed or returned something we could not use. */
public class TriageException extends RuntimeException {

    public TriageException(String message) {
        super(message);
    }

    public TriageException(String message, Throwable cause) {
        super(message, cause);
    }
}

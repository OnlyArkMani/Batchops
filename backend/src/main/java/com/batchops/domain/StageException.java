package com.batchops.domain;

/**
 * A pipeline stage failure that already knows whether retrying can help.
 * Permanent failures (bad file, bad schema) go straight to the incident engine; transient ones
 * (storage hiccup, timeout) walk the retry topics first.
 */
public class StageException extends RuntimeException {

    public enum Kind { TRANSIENT, PERMANENT }

    private final Kind kind;

    public StageException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public static StageException permanent(String message) {
        return new StageException(Kind.PERMANENT, message, null);
    }

    public static StageException permanent(String message, Throwable cause) {
        return new StageException(Kind.PERMANENT, message, cause);
    }

    public static StageException transientError(String message, Throwable cause) {
        return new StageException(Kind.TRANSIENT, message, cause);
    }

    public Kind kind() {
        return kind;
    }
}

package com.batchops.pipeline;

import com.batchops.domain.StageException;
import java.util.Map;

/** Permanent validation failure that carries the row-level report so it survives the rollback. */
public class ValidationFailedException extends StageException {

    private final transient Map<String, Object> report;

    public ValidationFailedException(String message, Map<String, Object> report) {
        super(Kind.PERMANENT, message, null);
        this.report = report;
    }

    public Map<String, Object> report() {
        return report;
    }
}

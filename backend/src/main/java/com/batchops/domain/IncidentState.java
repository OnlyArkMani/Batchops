package com.batchops.domain;

import java.util.Locale;

/** Incident lifecycle: open, in progress, resolved; a recurrence may reopen a resolved incident. */
public enum IncidentState {
    OPEN, IN_PROGRESS, RESOLVED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static IncidentState parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public boolean canMoveTo(IncidentState next) {
        return switch (this) {
            case OPEN -> next == IN_PROGRESS || next == RESOLVED;
            case IN_PROGRESS -> true;
            case RESOLVED -> next == OPEN;
        };
    }

    public IncidentState moveTo(IncidentState next) {
        if (!canMoveTo(next)) {
            throw new IllegalTransitionException("Incident", wire(), next.wire());
        }
        return next;
    }
}

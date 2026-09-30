package com.batchops.domain;

import java.util.Locale;

public enum TicketStatus {
    OPEN, IN_PROGRESS, RESOLVED, CLOSED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TicketStatus parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public boolean canMoveTo(TicketStatus next) {
        return switch (this) {
            case OPEN -> next != CLOSED;
            case IN_PROGRESS -> true;
            case RESOLVED -> next == CLOSED || next == OPEN;
            case CLOSED -> false;
        };
    }

    public TicketStatus moveTo(TicketStatus next) {
        if (!canMoveTo(next)) {
            throw new IllegalTransitionException("Ticket", wire(), next.wire());
        }
        return next;
    }
}

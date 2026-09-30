package com.batchops.domain;

/** Thrown when code tries to move an entity through a state change the state machine forbids. */
public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(String entity, String from, String to) {
        super(entity + " cannot move from " + from + " to " + to);
    }
}

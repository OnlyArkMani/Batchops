package com.batchops.schedule;

import java.util.List;
import java.util.Map;

/** A named unit of scheduled work. Jobs reference tasks by name, never by class or import path. */
@FunctionalInterface
public interface Task {
    String run(List<Object> args, Map<String, Object> kwargs) throws Exception;
}

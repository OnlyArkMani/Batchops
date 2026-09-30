package com.batchops.schedule;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Allow-list of runnable tasks. The original system executed whatever dotted path was stored in the
 * job config (arbitrary code execution for anyone who could edit a job); here an unknown name is
 * simply rejected.
 */
@Component
public class TaskRegistry {

    private final Map<String, Task> tasks = new TreeMap<>();

    public void register(String name, Task task) {
        tasks.put(name, task);
    }

    public Optional<Task> find(String name) {
        return Optional.ofNullable(name == null ? null : tasks.get(name));
    }

    public java.util.Set<String> names() {
        return tasks.keySet();
    }
}

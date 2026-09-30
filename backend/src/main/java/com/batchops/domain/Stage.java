package com.batchops.domain;

import java.util.List;
import java.util.Optional;

/**
 * The four independent consumers of the pipeline. Step names are kept identical to the original
 * five-step pipeline because the UI renders them (publisher performs two steps).
 */
public enum Stage {
    STANDARDIZER("standardizer", Topics.RECEIVED, Topics.STANDARDIZED, List.of("standardize_results")),
    VALIDATOR("validator", Topics.STANDARDIZED, Topics.VALIDATED, List.of("validate_results")),
    TRANSFORMER("transformer", Topics.VALIDATED, Topics.TRANSFORMED, List.of("transform_gradebook")),
    PUBLISHER("publisher", Topics.TRANSFORMED, Topics.PUBLISHED, List.of("generate_summary", "publish_results"));

    private final String stageName;
    private final String inputTopic;
    private final String outputTopic;
    private final List<String> steps;

    Stage(String stageName, String inputTopic, String outputTopic, List<String> steps) {
        this.stageName = stageName;
        this.inputTopic = inputTopic;
        this.outputTopic = outputTopic;
        this.steps = steps;
    }

    public String stageName() {
        return stageName;
    }

    public String inputTopic() {
        return inputTopic;
    }

    public String outputTopic() {
        return outputTopic;
    }

    public List<String> steps() {
        return steps;
    }

    public static Optional<Stage> fromName(String name) {
        for (Stage s : values()) {
            if (s.stageName.equals(name)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    public static Optional<Stage> fromInputTopic(String topic) {
        for (Stage s : values()) {
            if (s.inputTopic.equals(topic)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    public static Optional<Stage> fromStep(String stepName) {
        for (Stage s : values()) {
            if (s.steps.contains(stepName)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}

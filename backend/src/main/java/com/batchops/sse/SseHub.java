package com.batchops.sse;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fan-out of pipeline status events to browsers over Server-Sent Events. Every API instance consumes
 * uploads.status with its own consumer group, so each one can serve its own connected clients.
 */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("hello").data("connected"));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public void broadcast(String event, String jsonData) {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name(event).data(jsonData));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(e);
            }
        }
    }

    @Scheduled(fixedDelay = 25_000L)
    public void heartbeat() {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(e);
            }
        }
    }

    public int clients() {
        return emitters.size();
    }
}

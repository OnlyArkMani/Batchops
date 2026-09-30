package com.batchops.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/** Decoded pipeline message. The payload is a pointer (upload id + version), never the data itself. */
public record StageMessage(UUID eventId, UUID uploadId, int version, int attempt, String originTopic,
                           Map<String, Object> payload, String rawPayload) {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    public static StageMessage from(ConsumerRecord<String, String> rec, ObjectMapper mapper) {
        try {
            Map<String, Object> payload = mapper.readValue(rec.value(), MAP);
            UUID uploadId = UUID.fromString(String.valueOf(payload.get("upload_id")));
            int version = payload.get("version") instanceof Number n ? n.intValue() : 1;
            String eid = header(rec, EventHeaders.EVENT_ID);
            UUID eventId = eid == null ? UUID.nameUUIDFromBytes(
                    (rec.topic() + rec.partition() + ":" + rec.offset()).getBytes(StandardCharsets.UTF_8))
                    : UUID.fromString(eid);
            String att = header(rec, EventHeaders.ATTEMPT);
            int attempt = att == null ? 1 : Integer.parseInt(att);
            return new StageMessage(eventId, uploadId, version, attempt, header(rec, EventHeaders.ORIGIN_TOPIC),
                    payload, rec.value());
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed pipeline message at " + rec.topic() + "@" + rec.offset(), e);
        }
    }

    public static String header(ConsumerRecord<String, String> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    public static Map<String, Object> payloadOf(UUID uploadId, int version) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("upload_id", uploadId.toString());
        p.put("version", version);
        return p;
    }
}

package com.batchops.incident;

import com.batchops.api.ApiException;
import com.batchops.persistence.Incident;
import com.batchops.persistence.IncidentRepository;
import com.batchops.persistence.Ticket;
import com.batchops.persistence.TicketRepository;
import com.batchops.persistence.UserAccount;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService {

    private final TicketRepository tickets;
    private final IncidentRepository incidents;

    public TicketService(TicketRepository tickets, IncidentRepository incidents) {
        this.tickets = tickets;
        this.incidents = incidents;
    }

    private Ticket load(UUID id) {
        return tickets.findById(id).orElseThrow(() -> ApiException.notFound("Ticket not found"));
    }

    @Transactional
    public Ticket create(Map<String, Object> body, UserAccount actor) {
        Ticket t = new Ticket();
        t.source = "manual";
        t.title = IncidentService.nullable(body, "title");
        t.description = IncidentService.nullable(body, "description");
        t.assignee = IncidentService.nullable(body, "assignee");
        t.createdBy = actor.username;
        String incidentId = IncidentService.nullable(body, "incident");
        if (incidentId != null) {
            try {
                Incident inc = incidents.findById(UUID.fromString(incidentId))
                        .orElseThrow(() -> ApiException.badRequest("incident not found"));
                t.incident = inc;
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("incident must be a valid id");
            }
        }
        if (t.title == null && t.description == null) {
            throw ApiException.badRequest("title or description required");
        }
        t.timeline = Timelines.with(null, "Ticket created", actor.username, null);
        return tickets.save(t);
    }

    @Transactional
    public Ticket assign(UUID id, UserAccount actor, Map<String, Object> body) {
        Ticket t = load(id);
        String assignee = IncidentService.nullable(body, "assignee");
        if (assignee == null) {
            throw ApiException.badRequest("assignee required");
        }
        t.assignee = assignee;
        t.status = "in_progress";
        t.timeline = Timelines.with(t.timeline, "Assigned to " + assignee, actor.username, null);
        t.updatedAt = Instant.now();
        return tickets.save(t);
    }

    @Transactional
    public Ticket resolve(UUID id, UserAccount actor, Map<String, Object> body) {
        Ticket t = load(id);
        if ("resolved".equals(t.status)) {
            throw ApiException.badRequest("Ticket already resolved");
        }
        String notes = body == null || body.get("resolution_notes") == null ? "" : String.valueOf(body.get("resolution_notes"));
        String type = "automatic".equals(IncidentService.text(body, "resolution_type")) ? "automatic" : "manual";
        t.status = "resolved";
        t.resolvedBy = actor.username;
        t.resolutionType = type;
        t.resolutionNotes = notes;
        t.resolvedAt = Instant.now();
        t.updatedAt = t.resolvedAt;
        t.timeline = Timelines.with(t.timeline, "Ticket resolved by " + actor.username + " (" + type + ")",
                actor.username, notes);
        tickets.save(t);
        Incident inc = t.incident;
        if (inc != null) {
            inc.state = "resolved";
            inc.resolvedAt = t.resolvedAt;
            inc.resolvedBy = actor.username;
            if (!notes.isBlank()) {
                inc.correctiveAction = notes;
            }
            inc.timeline = Timelines.with(inc.timeline, "Resolved via ticket", actor.username, notes);
            inc.updatedAt = t.resolvedAt;
            incidents.save(inc);
        }
        return t;
    }
}

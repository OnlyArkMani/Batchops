package com.batchops.api;

import com.batchops.incident.TicketService;
import com.batchops.persistence.Ticket;
import com.batchops.persistence.TicketRepository;
import com.batchops.security.Access;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tickets are moderator-only end to end, exactly as the original TicketPermissions. */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private static final Set<String> ORDERABLE = Set.of("created_at", "updated_at", "status", "resolved_at");

    private final TicketRepository tickets;
    private final TicketService service;

    public TicketController(TicketRepository tickets, TicketService service) {
        this.tickets = tickets;
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String source, HttpServletRequest req) {
        Access.moderator();
        Specification<Ticket> spec = Specification.where(null);
        if (status != null && !status.isBlank()) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("status"), status));
        }
        if (source != null && !source.isBlank()) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("source"), source));
        }
        Page<Ticket> page = tickets.findAll(spec, Paging.pageable(req, ORDERABLE, "-created_at"));
        return Paging.envelope(page, req, Views::ticket);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        Access.moderator();
        return Views.ticket(tickets.findById(id).orElseThrow(() -> ApiException.notFound("Not found.")));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Views.ticket(service.create(body, Access.moderator())));
    }

    @PostMapping("/{id}/assign")
    public Map<String, Object> assign(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        return Views.ticket(service.assign(id, Access.moderator(), body == null ? new LinkedHashMap<>() : body));
    }

    @PostMapping("/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        return Views.ticket(service.resolve(id, Access.moderator(), body == null ? new LinkedHashMap<>() : body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        Access.moderator();
        tickets.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}

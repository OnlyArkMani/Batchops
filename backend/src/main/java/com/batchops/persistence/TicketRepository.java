package com.batchops.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TicketRepository extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {
    List<Ticket> findByIncidentIncidentIdAndStatusIn(UUID incidentId, Collection<String> statuses);

    long countByStatusIn(Collection<String> statuses);
}

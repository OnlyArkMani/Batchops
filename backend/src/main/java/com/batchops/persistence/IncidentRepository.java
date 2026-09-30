package com.batchops.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {
    Optional<Incident> findFirstByFingerprintAndStateNot(String fingerprint, String state);

    List<Incident> findByUploadUploadIdAndStateIn(UUID uploadId, Collection<String> states);

    long countByStateIn(Collection<String> states);
}

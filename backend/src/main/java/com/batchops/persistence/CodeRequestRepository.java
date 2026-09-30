package com.batchops.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CodeRequestRepository extends JpaRepository<CodeRequest, UUID> {
}

package com.joaopaulo.activation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivationRequestRepository
        extends JpaRepository<ActivationRequest, UUID>, JpaSpecificationExecutor<ActivationRequest> {

    Optional<ActivationRequest> findByExternalId(String externalId);

    Optional<ActivationRequest> findByIdAndClientId(UUID id, String clientId);

    @Query(value = """
            SELECT * FROM activation_request
             WHERE status = 'PENDING' AND next_attempt_at <= :now
             ORDER BY next_attempt_at
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ActivationRequest> lockDueForProcessing(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM activation_request
             WHERE status = 'PROCESSING' AND processing_started_at < :threshold
             ORDER BY processing_started_at
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ActivationRequest> lockStuckInProcessing(@Param("threshold") Instant threshold, @Param("limit") int limit);
}

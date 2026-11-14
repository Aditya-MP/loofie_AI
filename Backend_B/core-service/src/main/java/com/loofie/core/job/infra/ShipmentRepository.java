package com.loofie.core.job.infra;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.loofie.core.job.domain.Shipment;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {

    Optional<Shipment> findTopByJobIdOrderByAttemptDesc(UUID jobId);

    List<Shipment> findByJobIdOrderByAttempt(UUID jobId);

    long countByJobId(UUID jobId);
}

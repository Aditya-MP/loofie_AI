package com.loofie.core.job.infra;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.loofie.core.job.domain.Assignment;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {

    Optional<Assignment> findByJobIdAndStatus(UUID jobId, Assignment.Status status);

    List<Assignment> findByJobIdOrderByOpenedAt(UUID jobId);
}

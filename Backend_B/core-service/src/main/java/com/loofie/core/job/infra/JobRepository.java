package com.loofie.core.job.infra;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.loofie.core.job.domain.Job;

public interface JobRepository extends JpaRepository<Job, UUID> {
}

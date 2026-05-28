package com.fbtracker.backend;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BackfillJobRepository extends JpaRepository<BackfillJob, UUID> {
    List<BackfillJob> findAllByOrderByCreatedAtDesc();
    List<BackfillJob> findByStatus(BackfillJob.Status status);
}

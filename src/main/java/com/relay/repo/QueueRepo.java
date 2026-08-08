package com.relay.repo;

import com.relay.domain.QueueJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface QueueRepo extends JpaRepository<QueueJobEntity, Long> {
    Optional<QueueJobEntity> findByRunId(String runId);
}
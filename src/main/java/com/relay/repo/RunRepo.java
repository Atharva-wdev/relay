package com.relay.repo;

import com.relay.domain.RunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RunRepo extends JpaRepository<RunEntity, String> {
    List<RunEntity> findByWorkflowIdOrderByStartedAtDesc(String workflowId);
}
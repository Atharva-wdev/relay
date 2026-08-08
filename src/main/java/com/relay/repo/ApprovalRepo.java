package com.relay.repo;

import com.relay.domain.ApprovalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ApprovalRepo extends JpaRepository<ApprovalEntity, String> {
    List<ApprovalEntity> findByStatus(String status);
    boolean existsByRunIdAndStatus(String runId, String status);
}
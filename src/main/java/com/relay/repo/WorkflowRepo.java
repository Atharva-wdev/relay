package com.relay.repo;

import com.relay.domain.WorkflowEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRepo extends JpaRepository<WorkflowEntity, String> {}
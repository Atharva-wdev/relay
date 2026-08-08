package com.relay.repo;

import com.relay.domain.RunStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RunStepRepo extends JpaRepository<RunStepEntity, Long> {
    List<RunStepEntity> findByRunIdOrderBySequenceNoAsc(String runId);
}
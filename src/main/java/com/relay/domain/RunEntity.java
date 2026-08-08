package com.relay.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "runs")
@Getter @Setter
public class RunEntity {
    @Id
    @Column(name = "run_id")
    private String runId;

    private String workflowId;

    @Column(columnDefinition = "json")
    private String definitionSnapshot;

    private String status;
    private String triggerType;

    @Column(name = "input_json", columnDefinition = "json")
    private String inputJson;

    private String currentNodeId;
    private Integer stepsExecuted = 0;
    private Integer aiTokensUsed = 0;

    @Column(name = "error_json", columnDefinition = "json")
    private String errorJson;

    private Instant startedAt;
    private Instant finishedAt;
}
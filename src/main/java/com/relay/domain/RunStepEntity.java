package com.relay.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "run_steps")
@Getter @Setter
public class RunStepEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String runId;
    private String nodeId;
    private String nodeType;
    private Integer sequenceNo;
    private String status;
    private Integer attempt;

    @Column(columnDefinition = "json")
    private String resolvedInputJson;

    @Column(columnDefinition = "json")
    private String outputJson;

    private Integer tokensPrompt;
    private Integer tokensCompletion;
    private String idempotencyKey;
    private Instant startedAt;
    private Long durationMs;

    @Column(columnDefinition = "text")
    private String errorMessage;
}
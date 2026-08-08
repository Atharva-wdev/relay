package com.relay.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "queue_jobs")
@Getter @Setter
public class QueueJobEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String runId;
    private String status;
    private Instant availableAt;
    private Instant leasedUntil;
    private Integer attempts;
    private Instant createdAt;
}
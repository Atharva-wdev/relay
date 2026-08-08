package com.relay.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "approvals")
@Getter @Setter
public class ApprovalEntity {
    @Id
    private String id;

    private String runId;
    private String nodeId;

    @Column(columnDefinition = "text")
    private String message;

    private String status; // pending/approved/rejected
    private String decidedBy;
    private Instant decidedAt;
    private Instant createdAt;
}
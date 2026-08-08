package com.relay.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "workflows")
@Getter @Setter
public class WorkflowEntity {
    @Id
    private String id;

    private String name;
    private String status;

    @Column(name = "definition_json", columnDefinition = "json")
    private String definitionJson;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
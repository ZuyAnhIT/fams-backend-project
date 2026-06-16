package com.fams.modules.auth.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "health_checks")
public class HealthCheck {

    @Id
    private UUID id;

    private String message;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}
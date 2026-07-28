package com.fams.modules.employee.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "liveness_challenges")
public class LivenessChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(nullable = false, length = 20)
    private String purpose;

    /** Only meaningful for purpose='checkin' — the site the challenge was started for, so a
     *  challenge completed at Site A can't be carried over and consumed at Site B. */
    @Column(name = "site_id")
    private UUID siteId;

    // actions / embedding intentionally not mapped — fams-ai is the sole writer for the action
    // sequence (only it needs the array shape for verification logic) and the embedding (same
    // "Java never touches raw biometric vectors" convention as FaceProfile.embedding).

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "center_frame_path")
    private String centerFramePath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;
}

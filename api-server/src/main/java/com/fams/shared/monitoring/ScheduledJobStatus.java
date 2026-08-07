package com.fams.shared.monitoring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "scheduled_job_status")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledJobStatus {

    @Id
    @Column(name = "job_name", length = 100)
    private String jobName;

    @Column(name = "last_run_at", nullable = false)
    private OffsetDateTime lastRunAt;

    @Column(name = "last_status", nullable = false, length = 20)
    private String lastStatus;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "last_run_duration_ms")
    private Long lastRunDurationMs;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}

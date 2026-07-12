package com.fams.shared.monitoring;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScheduledJobStatusRepository extends JpaRepository<ScheduledJobStatus, String> {
}

package com.fams.modules.auth.repository;

import com.fams.modules.auth.entity.HealthCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface HealthCheckRepository extends JpaRepository<HealthCheck, UUID> {
}
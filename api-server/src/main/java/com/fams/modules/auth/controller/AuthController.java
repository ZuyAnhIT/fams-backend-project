package com.fams.modules.auth.controller;

import com.fams.modules.auth.repository.HealthCheckRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final HealthCheckRepository healthCheckRepository;

    public AuthController(HealthCheckRepository healthCheckRepository) {
        this.healthCheckRepository = healthCheckRepository;
    }

    @GetMapping("/api/v1/auth/health")
    public String health() {
        return "FAMS Auth Module is running";
    }

    @GetMapping("/api/v1/auth/db-health")
    public String databaseHealth() {
        return healthCheckRepository.findAll().get(0).getMessage();
    }
}
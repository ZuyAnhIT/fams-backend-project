package com.fams.modules.violation.service;

import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.violation.entity.Violation;
import com.fams.modules.violation.repository.ViolationRepository;
import com.fams.shared.dto.ExplanationResponse;
import com.fams.shared.dto.SubmitExplanationRequest;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
public class ViolationService {

    private final ViolationRepository violationRepository;
    private final EmployeeRepository employeeRepository;

    public ViolationService(ViolationRepository violationRepository,
                            EmployeeRepository employeeRepository) {
        this.violationRepository = violationRepository;
        this.employeeRepository = employeeRepository;
    }

    @Transactional
    public ExplanationResponse explainViolation(UUID tenantId, UUID violationId,
                                                 SubmitExplanationRequest request,
                                                 UUID callerUserId) {
        Employee employee = employeeRepository
                .findByUserIdAndTenantIdAndDeletedAtIsNull(callerUserId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee profile not found for this tenant"));

        Violation violation = violationRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(violationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Violation not found: " + violationId));

        if (!violation.getEmployeeId().equals(employee.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Violation does not belong to this employee");
        }

        violation.setEmployeeNote(request.getNote());
        violation.setEmployeePhotoUrl(request.getPhotoUrl());
        violationRepository.save(violation);

        log.info("Employee explanation submitted for violation: violationId={} employeeId={}",
                violationId, employee.getId());

        return ExplanationResponse.builder()
                .id(violation.getId())
                .employeeNote(violation.getEmployeeNote())
                .employeePhotoUrl(violation.getEmployeePhotoUrl())
                .updatedAt(OffsetDateTime.now())
                .build();
    }
}

package com.fams.modules.employee.service;

import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.employee.specification.EmployeeSpecification;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class EmployeeExportService {

    private static final String[] HEADERS = {
        "employeeCode", "firstName", "lastName", "email", "phone",
        "position", "department", "status", "hiredDate", "createdAt"
    };

    private final EmployeeRepository employeeRepository;
    private final UserRoleRepository userRoleRepository;
    private final TenantRepository tenantRepository;

    public EmployeeExportService(EmployeeRepository employeeRepository,
                                 UserRoleRepository userRoleRepository,
                                 TenantRepository tenantRepository) {
        this.employeeRepository = employeeRepository;
        this.userRoleRepository = userRoleRepository;
        this.tenantRepository = tenantRepository;
    }

    @Transactional(readOnly = true)
    public byte[] exportEmployees(UUID tenantId, String search, String status, String department,
                                  UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        if (!callerIsPlatformAdmin) {
            Set<String> permissions = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!permissions.contains("employees:list")) {
                throw new AccessDeniedException("You do not have permission to export employees in this tenant");
            }
        }

        Specification<Employee> spec = EmployeeSpecification.build(tenantId, search, status, department);
        List<Employee> employees = employeeRepository.findAll(spec);

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Employees");

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowNum = 1;
            for (Employee e : employees) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(nullSafe(e.getEmployeeCode()));
                row.createCell(1).setCellValue(nullSafe(e.getFirstName()));
                row.createCell(2).setCellValue(nullSafe(e.getLastName()));
                row.createCell(3).setCellValue(nullSafe(e.getEmail()));
                row.createCell(4).setCellValue(nullSafe(e.getPhone()));
                row.createCell(5).setCellValue(nullSafe(e.getPosition()));
                row.createCell(6).setCellValue(nullSafe(e.getDepartment()));
                row.createCell(7).setCellValue(nullSafe(e.getStatus()));
                row.createCell(8).setCellValue(e.getHiredDate() != null ? e.getHiredDate().toString() : "");
                row.createCell(9).setCellValue(e.getCreatedAt() != null ? e.getCreatedAt().toLocalDate().toString() : "");
            }

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            log.info("Employee export: tenantId={} rows={} by={}", tenantId, employees.size(), callerUserId);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate Excel file", e);
        }
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}

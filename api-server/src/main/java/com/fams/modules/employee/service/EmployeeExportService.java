package com.fams.modules.employee.service;

import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.employee.specification.EmployeeSpecification;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.service.SiteScopeService;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.shared.exception.ResourceNotFoundException;
import com.fams.shared.util.MaskingUtils;
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
import java.util.Optional;
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
    private final SiteScopeService siteScopeService;
    private final AssignmentRepository assignmentRepository;

    public EmployeeExportService(EmployeeRepository employeeRepository,
                                 UserRoleRepository userRoleRepository,
                                 TenantRepository tenantRepository,
                                 SiteScopeService siteScopeService,
                                 AssignmentRepository assignmentRepository) {
        this.employeeRepository = employeeRepository;
        this.userRoleRepository = userRoleRepository;
        this.tenantRepository = tenantRepository;
        this.siteScopeService = siteScopeService;
        this.assignmentRepository = assignmentRepository;
    }

    @Transactional(readOnly = true)
    public byte[] exportEmployees(UUID tenantId, String search, String status, String department,
                                  UUID callerUserId, boolean callerIsPlatformAdmin) {
        tenantRepository.findByIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        // Fetched regardless of callerIsPlatformAdmin (not just inside the permission-check
        // branch) because it's also needed below to decide whether email/phone get masked in
        // the export — found via audit (2026-08-06): this export wrote raw, unmasked email/phone
        // straight into the .xlsx via POI, entirely bypassing the @Masked/MaskedSerializer
        // mechanism that already masks those same fields in the JSON list endpoint gated by the
        // exact same "employees:list" permission — same permission, two different exposure
        // levels depending on which endpoint you hit. Bypass rule mirrors MaskedSerializer's own
        // (MaskedSerializer.isAdminCaller): platform admin or "employees:pii:read" holder sees
        // raw values (dedicated permission since 2026-08-06, was "users:create" — see
        // MaskedSerializer's comment), everyone else with just "employees:list" gets masked
        // values in the export too.
        Set<String> permissions = callerIsPlatformAdmin
                ? Set.of()
                : userRoleRepository.findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
        if (!callerIsPlatformAdmin && !permissions.contains("employees:list")) {
            throw new AccessDeniedException("You do not have permission to export employees in this tenant");
        }
        boolean bypassMasking = callerIsPlatformAdmin || permissions.contains("employees:pii:read");

        // Site-scoped callers (e.g. a SITE_SUPERVISOR restricted to specific sites) must not be
        // able to export employees outside their allowed sites — mirrors the same restriction
        // EmployeeService.listEmployees already applies, so export can't be used as a
        // site-scope bypass just because it skips pagination.
        Optional<Set<UUID>> allowedSiteIds =
                siteScopeService.resolveAllowedSiteIds(callerUserId, tenantId, callerIsPlatformAdmin);
        Set<UUID> restrictToEmployeeIds = null;
        if (allowedSiteIds.isPresent()) {
            if (allowedSiteIds.get().isEmpty()) {
                return emptyWorkbookBytes();
            }
            restrictToEmployeeIds = assignmentRepository
                    .findDistinctEmployeeIdsByTenantIdAndSiteIdIn(tenantId, allowedSiteIds.get());
            if (restrictToEmployeeIds.isEmpty()) {
                return emptyWorkbookBytes();
            }
        }

        Specification<Employee> spec = EmployeeSpecification.build(tenantId, search, status, department, restrictToEmployeeIds);
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
                row.createCell(3).setCellValue(bypassMasking ? nullSafe(e.getEmail()) : nullSafe(MaskingUtils.maskEmail(e.getEmail())));
                row.createCell(4).setCellValue(bypassMasking ? nullSafe(e.getPhone()) : nullSafe(MaskingUtils.maskPhone(e.getPhone())));
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

    /** A site-scoped caller with zero allowed employees still gets a valid (header-only) file
     *  back, not an error — an empty export is a normal outcome, not a failure. */
    private byte[] emptyWorkbookBytes() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Employees");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                headerRow.createCell(i).setCellValue(HEADERS[i]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate Excel file", e);
        }
    }
}

package com.fams.modules.employee.service;

import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.assignment.service.AssignmentService;
import com.fams.modules.audit.service.AuditLogService;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.employee.repository.FaceProfileRepository;
import com.fams.modules.randomcheck.service.ScheduledCheckCancelService;
import com.fams.modules.rbac.repository.RoleRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.rbac.service.SiteScopeService;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.subscription.service.PlanLimitEnforcementService;
import com.fams.modules.tenant.entity.Tenant;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.tenant.service.TenantSettingsService;
import com.fams.modules.workspace.repository.WorkspaceMemberRepository;
import com.fams.modules.workspace.repository.WorkspaceRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeImportWorkflowTest {

    @Mock EmployeeRepository employeeRepository;
    @Mock UserRoleRepository userRoleRepository;
    @Mock RoleRepository roleRepository;
    @Mock TenantRepository tenantRepository;
    @Mock PlanLimitEnforcementService planLimitEnforcementService;
    @Mock FaceProfileRepository faceProfileRepository;
    @Mock TenantSettingsService tenantSettingsService;
    @Mock AssignmentRepository assignmentRepository;
    @Mock SiteScopeService siteScopeService;
    @Mock WorkspaceMemberRepository workspaceMemberRepository;
    @Mock WorkspaceRepository workspaceRepository;
    @Mock AssignmentService assignmentService;
    @Mock FaceIdService faceIdService;
    @Mock ScheduledCheckCancelService scheduledCheckCancelService;
    @Mock AuditLogService auditLogService;
    @Mock SiteRepository siteRepository;

    @InjectMocks EmployeeService service;

    @Test
    void templateUsesVietnameseHeadersAndIncludesInstructions() throws Exception {
        UUID tenantId = UUID.randomUUID();
        allowPlatformImport(tenantId);

        byte[] bytes = service.createEmployeeImportTemplate(tenantId, UUID.randomUUID(), true);

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheetName(0)).isEqualTo("Danh sách nhân viên");
            assertThat(workbook.getSheet("Hướng dẫn")).isNotNull();
            Row header = workbook.getSheetAt(0).getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Mã nhân viên");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Họ và tên đệm");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Tên");
            assertThat(header.getCell(7).getStringCellValue()).isEqualTo("Ngày vào làm");
        }
    }

    @Test
    void validationReportsVietnameseFieldErrorsWithoutCreatingEmployees() throws Exception {
        UUID tenantId = UUID.randomUUID();
        allowPlatformImport(tenantId);
        MockMultipartFile file = workbook("nhan-vien.xlsx", workbook -> {
            addHeader(workbook);
            Row valid = workbook.getSheetAt(0).createRow(1);
            fillRow(valid, "NV-001", "Nguyễn Văn", "An", "an@example.com", "0901234567", "Kỹ sư", "Kỹ thuật", "01/09/2026");
            Row invalid = workbook.getSheetAt(0).createRow(2);
            fillRow(invalid, "NV 002", "Trần", "Bình", "email-sai", "09AB",
                    "C".repeat(101), "P".repeat(101), "31/02/2026");
        });

        var result = service.validateEmployeesImport(tenantId, file, UUID.randomUUID(), true);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getTotalRows()).isEqualTo(2);
        assertThat(result.getValidRows()).isEqualTo(1);
        assertThat(result.getInvalidRows()).isEqualTo(1);
        assertThat(result.getErrors()).extracting("field")
                .contains("employeeCode", "email", "phone", "position", "department", "hiredDate");
        assertThat(result.getErrors()).extracting("message")
                .allMatch(message -> !((String) message).isBlank());
        verify(employeeRepository, never()).save(any(Employee.class));
        verify(planLimitEnforcementService, never()).assertEmployeeCapacity(any(), anyInt(), any());
    }

    @Test
    void validationReportsMissingVietnameseStructureWithoutCreatingEmployees() throws Exception {
        UUID tenantId = UUID.randomUUID();
        allowPlatformImport(tenantId);
        MockMultipartFile file = workbook("nhan-vien.xlsx", workbook -> {
            Row header = workbook.getSheetAt(0).createRow(0);
            header.createCell(0).setCellValue("Email");
        });

        var result = service.validateEmployeesImport(tenantId, file, UUID.randomUUID(), true);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getTotalRows()).isZero();
        assertThat(result.getErrors()).extracting("field")
                .contains("firstname", "lastname", "file");
        verify(employeeRepository, never()).save(any(Employee.class));
    }

    @Test
    void importAcceptsVietnameseDateAndCreatesOnlyValidatedRows() throws Exception {
        UUID tenantId = UUID.randomUUID();
        allowPlatformImport(tenantId);
        MockMultipartFile file = workbook("nhan-vien.xlsx", workbook -> {
            addHeader(workbook);
            Row row = workbook.getSheetAt(0).createRow(1);
            fillRow(row, "NV-003", "Lê Minh", "Anh", "anh@example.com", "0903333333", "HR", "Nhân sự", "05/09/2026");
        });

        var result = service.importEmployees(tenantId, file, UUID.randomUUID(), true);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isZero();
        ArgumentCaptor<Employee> employee = ArgumentCaptor.forClass(Employee.class);
        verify(employeeRepository).save(employee.capture());
        assertThat(employee.getValue().getLastName()).isEqualTo("Lê Minh");
        assertThat(employee.getValue().getFirstName()).isEqualTo("Anh");
        assertThat(employee.getValue().getHiredDate()).isEqualTo(LocalDate.of(2026, 9, 5));
        verify(planLimitEnforcementService).assertEmployeeCapacity(eq(tenantId), eq(1), any());
    }

    @Test
    void errorsExportKeepsTheOriginalRowAndUsesVietnameseColumns() throws Exception {
        UUID tenantId = UUID.randomUUID();
        allowPlatformImport(tenantId);
        MockMultipartFile file = workbook("nhan-vien.xlsx", workbook -> {
            addHeader(workbook);
            Row invalid = workbook.getSheetAt(0).createRow(1);
            fillRow(invalid, "NV 004", "Phạm", "", "email-sai", "0904444444",
                    "Kỹ sư", "Kỹ thuật", "06/09/2026");
        });

        byte[] exported = service.exportImportErrors(tenantId, file, UUID.randomUUID(), true);

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(exported))) {
            assertThat(workbook.getSheetName(0)).isEqualTo("Dòng cần sửa");
            Row header = workbook.getSheetAt(0).getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Dòng");
            assertThat(header.getCell(9).getStringCellValue()).isEqualTo("Chi tiết lỗi");
            Row error = workbook.getSheetAt(0).getRow(1);
            assertThat(error.getCell(0).getNumericCellValue()).isEqualTo(2);
            assertThat(error.getCell(1).getStringCellValue()).isEqualTo("NV 004");
            assertThat(error.getCell(9).getStringCellValue())
                    .contains("Tên là trường bắt buộc", "Mã nhân viên");
        }
    }

    private void allowPlatformImport(UUID tenantId) {
        when(tenantRepository.findByIdAndDeletedAtIsNull(tenantId))
                .thenReturn(Optional.of(Tenant.builder().id(tenantId).build()));
    }

    private MockMultipartFile workbook(String filename, WorkbookWriter writer) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("Danh sách nhân viên");
            writer.write(workbook);
            workbook.write(output);
            return new MockMultipartFile("file", filename,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    output.toByteArray());
        }
    }

    private void addHeader(Workbook workbook) {
        Row header = workbook.getSheetAt(0).createRow(0);
        String[] labels = {"Mã nhân viên", "Họ và tên đệm", "Tên", "Email", "Số điện thoại",
                "Chức vụ", "Phòng ban", "Ngày vào làm"};
        for (int i = 0; i < labels.length; i++) header.createCell(i).setCellValue(labels[i]);
    }

    private void fillRow(Row row, String... values) {
        for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
    }

    @FunctionalInterface
    private interface WorkbookWriter {
        void write(Workbook workbook) throws Exception;
    }
}

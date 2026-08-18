package com.fams.modules.report.service;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.assignment.util.DayOfWeekBitmask;
import com.fams.modules.attendance.dto.response.AttendanceSummaryResponse;
import com.fams.modules.attendance.entity.AttendanceSummary;
import com.fams.modules.attendance.repository.AttendanceSummaryRepository;
import com.fams.modules.attendance.specification.AttendanceSummarySpecification;
import com.fams.modules.employee.entity.Employee;
import com.fams.modules.employee.entity.FaceProfile;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.employee.repository.FaceProfileRepository;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.attendance.dto.response.AttendanceHrMonthlyResponse;
import com.fams.modules.report.dto.response.DailyAttendanceReportResponse;
import com.fams.modules.report.dto.response.EmployeeRef;
import com.fams.modules.report.dto.response.FaceIdReportResponse;
import com.fams.modules.report.dto.response.FaceIdReportRow;
import com.fams.modules.report.dto.response.MonthlyAttendanceReportResponse;
import com.fams.modules.report.dto.response.SitePresenceEntry;
import com.fams.modules.report.dto.response.SitePresenceReportResponse;
import com.fams.modules.report.dto.response.ViolationReportResponse;
import com.fams.modules.site.entity.Site;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.checkin.entity.CheckinRecord;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.violation.dto.response.ViolationListResponse;
import com.fams.modules.violation.entity.Violation;
import com.fams.modules.violation.repository.ViolationRepository;
import com.fams.modules.violation.specification.ViolationSpecification;
import com.fams.modules.violation.util.ViolationSeverity;
import com.fams.shared.pagination.PageResponse;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageImpl;

@Slf4j
@Service
public class ReportService {

    private final AttendanceSummaryRepository summaryRepository;
    private final AssignmentRepository assignmentRepository;
    private final ViolationRepository violationRepository;
    private final SiteRepository siteRepository;
    private final CheckinRepository checkinRepository;
    private final UserRoleRepository userRoleRepository;
    private final EmployeeRepository employeeRepository;
    private final FaceProfileRepository faceProfileRepository;
    private final com.fams.modules.rbac.service.SiteScopeService siteScopeService;
    private final com.fams.modules.randomcheck.repository.RandomCheckConfigRepository randomCheckConfigRepository;
    private final com.fams.modules.audit.service.AuditLogService auditLogService;
    private final com.fams.modules.workspace.repository.WorkspaceMemberRepository workspaceMemberRepository;

    public ReportService(AttendanceSummaryRepository summaryRepository,
                         AssignmentRepository assignmentRepository,
                         ViolationRepository violationRepository,
                         SiteRepository siteRepository,
                         CheckinRepository checkinRepository,
                         UserRoleRepository userRoleRepository,
                         EmployeeRepository employeeRepository,
                         FaceProfileRepository faceProfileRepository,
                         com.fams.modules.rbac.service.SiteScopeService siteScopeService,
                         com.fams.modules.randomcheck.repository.RandomCheckConfigRepository randomCheckConfigRepository,
                         com.fams.modules.audit.service.AuditLogService auditLogService,
                         com.fams.modules.workspace.repository.WorkspaceMemberRepository workspaceMemberRepository) {
        this.summaryRepository = summaryRepository;
        this.assignmentRepository = assignmentRepository;
        this.violationRepository = violationRepository;
        this.siteRepository = siteRepository;
        this.checkinRepository = checkinRepository;
        this.userRoleRepository = userRoleRepository;
        this.employeeRepository = employeeRepository;
        this.faceProfileRepository = faceProfileRepository;
        this.siteScopeService = siteScopeService;
        this.randomCheckConfigRepository = randomCheckConfigRepository;
        this.auditLogService = auditLogService;
        this.workspaceMemberRepository = workspaceMemberRepository;
    }

    /** #122-#125 (2026-08-18): shared workspace-filter resolution for every report endpoint — a
     *  workspace has no direct column on AttendanceSummary/Violation (relationship is via
     *  WorkspaceMember), so every report that wants to filter by it needs this same employee-ID
     *  resolution first. Returns null (no filter) when workspaceId is null; returns an empty set
     *  (not null) when the workspace has zero members, which callers must treat as "no results",
     *  not "no filter" — the two are NOT the same when a real workspaceId was requested. */
    private java.util.Set<UUID> resolveWorkspaceEmployeeIds(UUID tenantId, UUID workspaceId) {
        if (workspaceId == null) return null;
        return workspaceMemberRepository.findDistinctEmployeeIdsByTenantIdAndWorkspaceId(tenantId, workspaceId);
    }

    @Transactional(readOnly = true)
    public DailyAttendanceReportResponse getDailyAttendanceReport(
            UUID tenantId, LocalDate date, UUID siteId, UUID workspaceId, int page, int size,
            UUID callerUserId, boolean callerIsPlatformAdmin) {

        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("reports:list")) {
                throw new AccessDeniedException("reports:list permission required to view attendance reports");
            }
        }

        // Site-scope enforcement (audit 2026-08-04) — this was the one report endpoint that
        // never got the fix already applied to monthly attendance/export back on 2026-07-31: a
        // site-restricted caller (e.g. SITE_SUPERVISOR) could pass ANY siteId, or omit it and see
        // every site's daily attendance. Reuses the exact same helper as the monthly report.
        UUID effectiveSiteId;
        try {
            effectiveSiteId = resolveSiteFilterForReports(callerUserId, tenantId, siteId, callerIsPlatformAdmin);
        } catch (NoSitesAllowedForReport e) {
            return DailyAttendanceReportResponse.builder()
                    .date(date).siteId(siteId)
                    .absentEmployees(List.of())
                    .records(PageResponse.from(Page.empty(PageRequest.of(page, size))))
                    .build();
        }

        // #122 (2026-08-18): workspace filter — empty (non-null) set means a real workspaceId
        // was requested but has zero members, must return no data rather than falling through to
        // "no filter".
        Set<UUID> workspaceEmployeeIds = resolveWorkspaceEmployeeIds(tenantId, workspaceId);
        if (workspaceEmployeeIds != null && workspaceEmployeeIds.isEmpty()) {
            return DailyAttendanceReportResponse.builder()
                    .date(date).siteId(siteId)
                    .absentEmployees(List.of())
                    .records(PageResponse.from(Page.empty(PageRequest.of(page, size))))
                    .build();
        }

        Specification<AttendanceSummary> spec =
                AttendanceSummarySpecification.build(tenantId, null, effectiveSiteId, null, date, date, workspaceEmployeeIds);

        // Load all records for the day to compute aggregate stats across the full result set
        List<AttendanceSummary> allForDay = summaryRepository.findAll(spec);

        int totalPresent = allForDay.size();
        int totalLate = (int) allForDay.stream().filter(AttendanceSummary::isLate).count();
        int totalEarlyLeave = (int) allForDay.stream().filter(AttendanceSummary::isEarlyLeave).count();
        int totalMissingCheckout = (int) allForDay.stream().filter(AttendanceSummary::isMissingCheckout).count();
        int totalWorkMinutes = allForDay.stream().mapToInt(AttendanceSummary::getTotalWorkMinutes).sum();
        int totalOtMinutes = allForDay.stream().mapToInt(AttendanceSummary::getOtMinutes).sum();

        // Absent = employees with active assignments on the date but no attendance record
        Set<UUID> presentEmployeeIds = allForDay.stream()
                .map(AttendanceSummary::getEmployeeId)
                .collect(Collectors.toSet());

        int dateBit = DayOfWeekBitmask.bitForDate(date);
        List<Assignment> activeAssignments = effectiveSiteId != null
                ? assignmentRepository.findActiveAssignmentsForSiteOnDate(tenantId, effectiveSiteId, date, dateBit)
                : assignmentRepository.findActiveAssignmentsWithShiftForDate(tenantId, date, dateBit);

        List<UUID> absentEmployeeIdList = activeAssignments.stream()
                .map(Assignment::getEmployeeId)
                .filter(id -> !presentEmployeeIds.contains(id))
                .filter(id -> workspaceEmployeeIds == null || workspaceEmployeeIds.contains(id))
                .distinct()
                .collect(Collectors.toList());
        List<EmployeeRef> absentEmployeeIds = toEmployeeRefs(tenantId, absentEmployeeIdList);

        // Paginated individual records
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "employeeId"));
        Page<AttendanceSummaryResponse> recordPage = summaryRepository.findAll(spec, pageable)
                .map(this::toResponse);

        log.info("Daily attendance report: tenantId={} date={} siteId={} present={} absent={} late={}",
                tenantId, date, siteId, totalPresent, absentEmployeeIds.size(), totalLate);

        return DailyAttendanceReportResponse.builder()
                .date(date)
                .siteId(siteId)
                .totalPresent(totalPresent)
                .totalAbsent(absentEmployeeIds.size())
                .totalLate(totalLate)
                .totalEarlyLeave(totalEarlyLeave)
                .totalMissingCheckout(totalMissingCheckout)
                .totalWorkMinutes(totalWorkMinutes)
                .totalOtMinutes(totalOtMinutes)
                .absentEmployees(absentEmployeeIds)
                .records(PageResponse.from(recordPage))
                .build();
    }

    @Transactional(readOnly = true)
    public MonthlyAttendanceReportResponse getMonthlyAttendanceReport(
            UUID tenantId, int year, int month, UUID siteId, UUID workspaceId, int page, int size,
            UUID callerUserId, boolean callerIsPlatformAdmin) {

        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("reports:list")) {
                throw new AccessDeniedException("reports:list permission required to view attendance reports");
            }
        }

        UUID effectiveSiteId;
        try {
            effectiveSiteId = resolveSiteFilterForReports(callerUserId, tenantId, siteId, callerIsPlatformAdmin);
        } catch (NoSitesAllowedForReport e) {
            return MonthlyAttendanceReportResponse.builder()
                    .year(year).month(month).siteId(siteId)
                    .records(PageResponse.from(Page.empty(PageRequest.of(page, size))))
                    .build();
        }

        List<AttendanceHrMonthlyResponse> aggregated =
                aggregateMonthlyRows(tenantId, siteId, effectiveSiteId, year, month, workspaceId);

        // Tenant-wide totals — computed once here, over every row in scope (not just the page
        // being returned), so the UI can show accurate month totals alongside the paginated list.
        int totalEmployees        = aggregated.size();
        int totalPresentDays      = aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getPresentDays).sum();
        int totalWorkMinutes      = aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getTotalWorkMinutes).sum();
        int totalLateDays         = aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getLateDays).sum();
        int totalLateMinutes      = aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getTotalLateMinutes).sum();
        int totalEarlyLeaveDays   = aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getEarlyLeaveDays).sum();
        int totalEarlyLeaveMinutes = aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getTotalEarlyLeaveMinutes).sum();
        int totalMissingCheckoutDays = aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getMissingCheckoutDays).sum();
        int totalOtMinutes        = aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getTotalOtMinutes).sum();
        int totalRowsWithPendingReview = (int) aggregated.stream().filter(r -> r.getDaysWithPendingReview() > 0).count();
        int totalRowsWithRejectedSession = (int) aggregated.stream().filter(r -> r.getDaysWithRejectedSession() > 0).count();
        int totalRowsWithRandomCheckFailure = (int) aggregated.stream().filter(r -> r.getDaysWithRandomCheckFailure() > 0).count();

        int total    = aggregated.size();
        int fromIdx  = Math.min(page * size, total);
        int toIdx    = Math.min(fromIdx + size, total);
        List<AttendanceHrMonthlyResponse> pageContent = aggregated.subList(fromIdx, toIdx);
        PageRequest pageable = PageRequest.of(page, size);
        PageResponse<AttendanceHrMonthlyResponse> recordPage =
                PageResponse.from(new PageImpl<>(pageContent, pageable, total));

        log.info("Monthly attendance report: tenantId={} {}/{} siteId={} employees={} presentDays={} " +
                "rowsWithPendingReview={} rowsWithRejected={} rowsWithRandomCheckFailure={}",
                tenantId, year, month, siteId, totalEmployees, totalPresentDays,
                totalRowsWithPendingReview, totalRowsWithRejectedSession, totalRowsWithRandomCheckFailure);

        return MonthlyAttendanceReportResponse.builder()
                .year(year)
                .month(month)
                .siteId(siteId)
                .totalEmployees(totalEmployees)
                .totalPresentDays(totalPresentDays)
                .totalWorkMinutes(totalWorkMinutes)
                .totalLateDays(totalLateDays)
                .totalLateMinutes(totalLateMinutes)
                .totalEarlyLeaveDays(totalEarlyLeaveDays)
                .totalEarlyLeaveMinutes(totalEarlyLeaveMinutes)
                .totalMissingCheckoutDays(totalMissingCheckoutDays)
                .totalOtMinutes(totalOtMinutes)
                .totalRowsWithPendingReview(totalRowsWithPendingReview)
                .totalRowsWithRejectedSession(totalRowsWithRejectedSession)
                .totalRowsWithRandomCheckFailure(totalRowsWithRandomCheckFailure)
                .records(recordPage)
                .build();
    }

    @Transactional(readOnly = true)
    public byte[] exportMonthlyAttendance(UUID tenantId, int year, int month, UUID siteId, UUID workspaceId,
                                          boolean confirmDespiteWarnings,
                                          UUID callerUserId, boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            // attendance:export, not reports:export — found via audit (2026-07-31): the seed
            // migration (V13) creates and assigns a dedicated attendance:export permission for
            // exactly this action, but the code was checking the unrelated reports:export
            // permission instead, so a role granted attendance:export specifically could not
            // actually export, and reports:export (a broader "reports module" permission not
            // necessarily meant for payroll-affecting attendance data) could.
            if (!perms.contains("attendance:export")) {
                throw new AccessDeniedException("attendance:export permission required to export attendance data");
            }
        }

        UUID effectiveSiteId;
        try {
            effectiveSiteId = resolveSiteFilterForReports(callerUserId, tenantId, siteId, callerIsPlatformAdmin);
        } catch (NoSitesAllowedForReport e) {
            effectiveSiteId = siteId; // no allowed sites -> aggregateMonthlyRows below returns empty, correct no-data export
        }

        List<AttendanceHrMonthlyResponse> aggregated =
                aggregateMonthlyRows(tenantId, siteId, effectiveSiteId, year, month, workspaceId);

        // Payroll readiness guard (2026-07-31 audit): refuse to export silently when the scope
        // still has unconfirmed/rejected sessions feeding into it — the whole point of the V79
        // status-filtering fix was that pending_review/rejected sessions must not affect pay
        // until resolved; exporting them into a payroll file without an explicit, informed
        // override defeats that. Caller must pass confirmDespiteWarnings=true to proceed anyway
        // (e.g. because the pending items are known to be irrelevant to this specific run).
        // hasRandomCheckFailure rows are included in this same readiness guard — it's an
        // informational signal (never mutates pay fields, see V80 migration), but "informational"
        // still means HR should look before finalizing payroll, same rationale as pending/rejected.
        long rowsWithPendingReview = aggregated.stream().filter(r -> r.getDaysWithPendingReview() > 0).count();
        long rowsWithRejected = aggregated.stream().filter(r -> r.getDaysWithRejectedSession() > 0).count();
        long rowsWithRandomCheckFailure = aggregated.stream().filter(r -> r.getDaysWithRandomCheckFailure() > 0).count();
        if (!confirmDespiteWarnings && (rowsWithPendingReview > 0 || rowsWithRejected > 0 || rowsWithRandomCheckFailure > 0)) {
            throw new com.fams.shared.exception.BusinessException(
                    "ATTENDANCE_NOT_READY",
                    String.format("Có %d nhân viên còn chấm công chờ duyệt, %d nhân viên có chấm công bị từ chối, "
                                    + "và %d nhân viên có kiểm tra ngẫu nhiên thất bại/không phản hồi trong tháng này "
                                    + "— số liệu có thể chưa chính xác. Xác nhận để xuất file dù vậy?",
                            rowsWithPendingReview, rowsWithRejected, rowsWithRandomCheckFailure),
                    org.springframework.http.HttpStatus.CONFLICT,
                    String.format("Export blocked: %d rows with pending review, %d rows with rejected sessions, "
                                    + "%d rows with random-check failure in scope tenantId=%s %d/%d siteId=%s. "
                                    + "Pass confirmDespiteWarnings=true to override.",
                            rowsWithPendingReview, rowsWithRejected, rowsWithRandomCheckFailure,
                            tenantId, year, month, siteId));
        }

        Set<UUID> empIds = aggregated.stream().map(AttendanceHrMonthlyResponse::getEmployeeId).collect(Collectors.toSet());
        Map<UUID, String> empCodes = employeeRepository.findAllByTenantIdAndIdInAndDeletedAtIsNull(tenantId, empIds)
                .stream().collect(Collectors.toMap(Employee::getId,
                        e -> e.getEmployeeCode() != null ? e.getEmployeeCode() : "", (a, b) -> a));

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(
                    String.format("Attendance %d-%02d", year, month));

            Row metaRow = sheet.createRow(0);
            metaRow.createCell(0).setCellValue("Exported at");
            metaRow.createCell(1).setCellValue(OffsetDateTime.now().toString());
            metaRow.createCell(3).setCellValue("Rows with pending review");
            metaRow.createCell(4).setCellValue(rowsWithPendingReview);
            metaRow.createCell(5).setCellValue("Rows with rejected sessions");
            metaRow.createCell(6).setCellValue(rowsWithRejected);
            metaRow.createCell(7).setCellValue("Rows with random-check failure");
            metaRow.createCell(8).setCellValue(rowsWithRandomCheckFailure);

            String[] headers = {
                "Employee Code", "Employee Name", "Site Name", "Year", "Month",
                "Present Days", "Work Minutes (incl. OT)",
                "Late Days", "Late Minutes",
                "Early Leave Days", "Early Leave Minutes",
                "OT Minutes", "Missing Checkout Days",
                "Days With Pending Review", "Days With Rejected Session",
                "Days With Random-Check Failure", "Exceeds Failure Threshold", "Violation Count"
            };
            Row headerRow = sheet.createRow(1);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }

            int rowNum = 2;
            for (AttendanceHrMonthlyResponse rec : aggregated) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(empCodes.getOrDefault(rec.getEmployeeId(), rec.getEmployeeId().toString()));
                row.createCell(1).setCellValue(rec.getEmployeeName() != null ? rec.getEmployeeName() : "");
                row.createCell(2).setCellValue(rec.getSiteName() != null ? rec.getSiteName() : "");
                row.createCell(3).setCellValue(rec.getYear());
                row.createCell(4).setCellValue(rec.getMonth());
                row.createCell(5).setCellValue(rec.getPresentDays());
                row.createCell(6).setCellValue(rec.getTotalWorkMinutes());
                row.createCell(7).setCellValue(rec.getLateDays());
                row.createCell(8).setCellValue(rec.getTotalLateMinutes());
                row.createCell(9).setCellValue(rec.getEarlyLeaveDays());
                row.createCell(10).setCellValue(rec.getTotalEarlyLeaveMinutes());
                row.createCell(11).setCellValue(rec.getTotalOtMinutes());
                row.createCell(12).setCellValue(rec.getMissingCheckoutDays());
                row.createCell(13).setCellValue(rec.getDaysWithPendingReview());
                row.createCell(14).setCellValue(rec.getDaysWithRejectedSession());
                row.createCell(15).setCellValue(rec.getDaysWithRandomCheckFailure());
                row.createCell(16).setCellValue(rec.isExceedsRandomCheckFailureThreshold());
                row.createCell(17).setCellValue(rec.getViolationCount());
            }

            // #124 (2026-08-18): AC calls for a totals row so HR/accounting doesn't have to sum
            // the file themselves before handing it off for payroll — previously the export
            // ended right after the last employee row with no summary.
            Row totalRow = sheet.createRow(rowNum);
            totalRow.createCell(0).setCellValue("TOTAL");
            totalRow.createCell(5).setCellValue(aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getPresentDays).sum());
            totalRow.createCell(6).setCellValue(aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getTotalWorkMinutes).sum());
            totalRow.createCell(7).setCellValue(aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getLateDays).sum());
            totalRow.createCell(8).setCellValue(aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getTotalLateMinutes).sum());
            totalRow.createCell(9).setCellValue(aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getEarlyLeaveDays).sum());
            totalRow.createCell(10).setCellValue(aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getTotalEarlyLeaveMinutes).sum());
            totalRow.createCell(11).setCellValue(aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getTotalOtMinutes).sum());
            totalRow.createCell(12).setCellValue(aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getMissingCheckoutDays).sum());

            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            log.info("Attendance export: tenantId={} {}/{} siteId={} rows={} pendingReviewRows={} rejectedRows={} " +
                    "randomCheckFailureRows={} by={}",
                    tenantId, year, month, siteId, aggregated.size(), rowsWithPendingReview, rowsWithRejected,
                    rowsWithRandomCheckFailure, callerUserId);

            // #124 (2026-08-18): payroll-affecting exports need an audit trail — who exported
            // what scope and when, same rationale as every other HR mutation this session.
            try {
                auditLogService.record(
                        tenantId, callerUserId, null,
                        "AttendanceExport", String.format("%d-%02d", year, month), "EXPORT_ATTENDANCE",
                        null, java.util.Map.of(
                                "year", year, "month", month,
                                "siteId", siteId != null ? siteId.toString() : "",
                                "rows", aggregated.size()),
                        com.fams.shared.security.HttpRequestUtils.currentRequestId(),
                        com.fams.shared.security.HttpRequestUtils.currentIpAddress(),
                        com.fams.shared.security.HttpRequestUtils.currentUserAgent());
            } catch (Exception e) {
                log.warn("Failed to record audit log for EXPORT_ATTENDANCE tenantId={} {}/{}: {}",
                        tenantId, year, month, e.getMessage());
            }

            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate Excel export", e);
        }
    }

    /** Shared by getMonthlyAttendanceReport and exportMonthlyAttendance — uses the DB-level
     *  GROUP BY aggregate (AttendanceSummaryRepository.aggregateMonthly), not the old
     *  load-everything-then-group-in-Java approach, and hydrates employee/site display names. */
    private List<AttendanceHrMonthlyResponse> aggregateMonthlyRows(
            UUID tenantId, UUID requestedSiteId, UUID effectiveSiteId, int year, int month, UUID workspaceId) {
        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to   = from.plusMonths(1);

        // #123 (2026-08-18): workspace filter — resolved once, applied as a post-fetch filter on
        // the native aggregate rows below (kept out of the already-complex aggregateMonthly SQL).
        Set<UUID> workspaceEmployeeIds = resolveWorkspaceEmployeeIds(tenantId, workspaceId);

        List<com.fams.modules.attendance.repository.AttendanceMonthlyAggregateProjection> rows = summaryRepository
                .aggregateMonthly(tenantId, null, effectiveSiteId, from, to, null, null, "asc",
                        PageRequest.of(0, Integer.MAX_VALUE))
                .getContent();
        if (workspaceEmployeeIds != null) {
            rows = rows.stream()
                    .filter(r -> workspaceEmployeeIds.contains(r.getEmployeeId()))
                    .collect(Collectors.toList());
        }

        Set<UUID> empIds = rows.stream()
                .map(com.fams.modules.attendance.repository.AttendanceMonthlyAggregateProjection::getEmployeeId)
                .collect(Collectors.toSet());
        Set<UUID> siteIds = rows.stream()
                .map(com.fams.modules.attendance.repository.AttendanceMonthlyAggregateProjection::getSiteId)
                .collect(Collectors.toSet());
        Map<UUID, String> empNames = empIds.isEmpty() ? Map.of()
                : employeeRepository.findAllByTenantIdAndIdInAndDeletedAtIsNull(tenantId, empIds).stream()
                        .collect(Collectors.toMap(Employee::getId, e -> (e.getFirstName() + " " + e.getLastName()).trim()));
        Map<UUID, String> siteNames = siteIds.isEmpty() ? Map.of()
                : siteRepository.findAllByTenantIdAndIdInAndDeletedAtIsNull(tenantId, siteIds).stream()
                        .collect(Collectors.toMap(Site::getId, Site::getName));
        int failureThreshold = randomCheckConfigRepository.findTenantDefault(tenantId)
                .map(com.fams.modules.randomcheck.entity.RandomCheckConfig::getFailureEscalationThreshold)
                .orElse(Integer.MAX_VALUE);

        // #123 (2026-08-18): violationCount per employee+site — see ViolationRepository
        // #countByEmployeeAndSiteInRange javadoc for why this is a separate query merged in Java
        // rather than folded into the native aggregateMonthly query above.
        Map<String, Integer> violationCounts = violationRepository
                .countByEmployeeAndSiteInRange(tenantId, effectiveSiteId, from, to).stream()
                .collect(Collectors.toMap(
                        r -> r[0] + "|" + r[1],
                        r -> ((Long) r[2]).intValue()));

        return rows.stream()
                .map(row -> AttendanceHrMonthlyResponse.builder()
                        .tenantId(tenantId)
                        .employeeId(row.getEmployeeId())
                        .employeeName(empNames.get(row.getEmployeeId()))
                        .siteId(row.getSiteId())
                        .siteName(siteNames.get(row.getSiteId()))
                        .year(year)
                        .month(month)
                        .presentDays(row.getPresentDays().intValue())
                        .totalWorkMinutes(row.getTotalWorkMinutes().intValue())
                        .lateDays(row.getLateDays().intValue())
                        .totalLateMinutes(row.getTotalLateMinutes().intValue())
                        .earlyLeaveDays(row.getEarlyLeaveDays().intValue())
                        .totalEarlyLeaveMinutes(row.getTotalEarlyLeaveMinutes().intValue())
                        .totalOtMinutes(row.getTotalOtMinutes().intValue())
                        .missingCheckoutDays(row.getMissingCheckoutDays().intValue())
                        .daysWithPendingReview(row.getDaysWithPendingReview().intValue())
                        .daysWithRejectedSession(row.getDaysWithRejectedSession().intValue())
                        .daysWithRandomCheckFailure(row.getDaysWithRandomCheckFailure().intValue())
                        .exceedsRandomCheckFailureThreshold(
                                row.getDaysWithRandomCheckFailure().intValue() >= failureThreshold)
                        .violationCount(violationCounts.getOrDefault(
                                row.getEmployeeId() + "|" + row.getSiteId(), 0))
                        .build())
                .collect(Collectors.toList());
    }

    /** Marker for "caller has zero allowed sites" — mirrors AttendanceSummaryService's identical
     *  pattern (not shared cross-service; small enough not to warrant the coupling). */
    private static final class NoSitesAllowedForReport extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** Resolves the effective siteId to query with, enforcing site-scope for a restricted caller
     *  (e.g. SITE_SUPERVISOR) — found missing entirely on both attendance report/export methods
     *  in an audit (2026-07-31): a site-scoped caller could previously pass ANY siteId (or none,
     *  seeing every site) with no restriction check at all. */
    private UUID resolveSiteFilterForReports(UUID callerUserId, UUID tenantId, UUID requestedSiteId,
                                              boolean callerIsPlatformAdmin) {
        java.util.Optional<Set<UUID>> allowed =
                siteScopeService.resolveAllowedSiteIds(callerUserId, tenantId, callerIsPlatformAdmin);
        if (allowed.isEmpty()) {
            return requestedSiteId;
        }
        Set<UUID> allowedSites = allowed.get();
        if (allowedSites.isEmpty()) {
            throw new NoSitesAllowedForReport();
        }
        if (requestedSiteId != null) {
            if (!allowedSites.contains(requestedSiteId)) {
                throw new AccessDeniedException("You do not have permission to view/export attendance for this site");
            }
            return requestedSiteId;
        }
        if (allowedSites.size() == 1) {
            return allowedSites.iterator().next();
        }
        throw new AccessDeniedException(
                "You are scoped to multiple sites — pass a specific siteId to view/export this report");
    }

    @Transactional(readOnly = true)
    public ViolationReportResponse getViolationReport(
            UUID tenantId, LocalDate from, LocalDate to,
            UUID siteId, UUID employeeId, String violationType, UUID workspaceId,
            int page, int size,
            UUID callerUserId, boolean callerIsPlatformAdmin) {

        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("reports:list")) {
                throw new AccessDeniedException("reports:list permission required to view violation reports");
            }
        }

        // Site-scope enforcement (audit 2026-08-04) — same gap as the daily attendance report:
        // a site-restricted caller could previously pass any siteId (or none) and see every
        // site's violations tenant-wide.
        UUID effectiveSiteId;
        try {
            effectiveSiteId = resolveSiteFilterForReports(callerUserId, tenantId, siteId, callerIsPlatformAdmin);
        } catch (NoSitesAllowedForReport e) {
            return ViolationReportResponse.builder()
                    .from(from).to(to).siteId(siteId).employeeId(employeeId).violationType(violationType)
                    .byViolationType(Map.of()).bySite(Map.of()).byEmployee(Map.of()).bySeverity(Map.of())
                    .records(PageResponse.from(Page.empty(PageRequest.of(page, size))))
                    .build();
        }

        // #125 (2026-08-18): workspace filter — empty (non-null) set means the workspace has no
        // members, must return no data rather than falling through to "no filter".
        Set<UUID> workspaceEmployeeIds = resolveWorkspaceEmployeeIds(tenantId, workspaceId);
        if (workspaceEmployeeIds != null && workspaceEmployeeIds.isEmpty()) {
            return ViolationReportResponse.builder()
                    .from(from).to(to).siteId(siteId).employeeId(employeeId).violationType(violationType)
                    .byViolationType(Map.of()).bySite(Map.of()).byEmployee(Map.of()).bySeverity(Map.of())
                    .records(PageResponse.from(Page.empty(PageRequest.of(page, size))))
                    .build();
        }

        org.springframework.data.jpa.domain.Specification<Violation> spec =
                ViolationSpecification.build(tenantId, employeeId, effectiveSiteId, violationType, null, from, to,
                        null, null, workspaceEmployeeIds);

        // Load all matching violations for aggregate stats
        List<Violation> all = violationRepository.findAll(spec);

        int totalViolations      = all.size();
        int resolvedCount        = (int) all.stream().filter(Violation::isResolved).count();
        int unresolvedCount      = totalViolations - resolvedCount;
        int affectsAttendanceCount = (int) all.stream().filter(Violation::isAffectsAttendance).count();

        Map<String, Long> byViolationType = all.stream()
                .collect(Collectors.groupingBy(Violation::getViolationType, Collectors.counting()));

        // Severity is derived from violationType (audit 2026-08-04) — the system tracks no
        // separate manually-set severity field, and none of the 8 violation-handling stories
        // asked for HR to set one; deriving it from type is the same approach real attendance
        // platforms (Deputy's exception categories) use, and keeps the breakdown meaningful
        // without adding a new column/migration for a value nothing else in the system reads.
        Map<String, Long> bySeverity = all.stream()
                .collect(Collectors.groupingBy(
                        v -> ViolationSeverity.of(v.getViolationType()).name(),
                        Collectors.counting()));

        Map<String, Long> bySite = all.stream()
                .collect(Collectors.groupingBy(v -> v.getSiteId().toString(), Collectors.counting()));

        Map<String, Long> byEmployee = all.stream()
                .collect(Collectors.groupingBy(v -> v.getEmployeeId().toString(), Collectors.counting()));

        // Paginated records
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "checkDate"));
        Page<ViolationListResponse> recordPage = violationRepository.findAll(spec, pageable)
                .map(this::toViolationListResponse);

        log.info("Violation report: tenantId={} from={} to={} siteId={} employeeId={} total={}",
                tenantId, from, to, effectiveSiteId, employeeId, totalViolations);

        return ViolationReportResponse.builder()
                .from(from)
                .to(to)
                .siteId(siteId)
                .employeeId(employeeId)
                .violationType(violationType)
                .totalViolations(totalViolations)
                .resolvedCount(resolvedCount)
                .unresolvedCount(unresolvedCount)
                .affectsAttendanceCount(affectsAttendanceCount)
                .byViolationType(byViolationType)
                .bySeverity(bySeverity)
                .bySite(bySite)
                .byEmployee(byEmployee)
                .records(PageResponse.from(recordPage))
                .build();
    }

    @Transactional(readOnly = true)
    public SitePresenceReportResponse getSitePresenceReport(
            UUID tenantId, UUID siteId, int page, int size,
            UUID callerUserId, boolean callerIsPlatformAdmin) {

        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("reports:list")) {
                throw new AccessDeniedException("reports:list permission required to view site presence report");
            }
        }

        // Site-scope enforcement (audit 2026-08-04) — this endpoint iterates the site LIST
        // directly (not a single siteId like attendance/violation reports), so it needs the
        // Face-ID-report-style multi-site pattern rather than resolveSiteFilterForReports. A
        // site-restricted caller (now including SITE_SUPERVISOR, granted reports:list in V84
        // specifically so they could see this report) must not see every site's headcount —
        // this was the exact gap the V84 grant would otherwise have reintroduced.
        java.util.Optional<Set<UUID>> allowedSiteIds =
                siteScopeService.resolveAllowedSiteIds(callerUserId, tenantId, callerIsPlatformAdmin);
        if (siteId != null && allowedSiteIds.isPresent() && !allowedSiteIds.get().contains(siteId)) {
            throw new AccessDeniedException("You do not have permission to view presence for this site");
        }

        // Fetch active sites for this tenant (optionally filtered to one site)
        org.springframework.data.jpa.domain.Specification<Site> siteSpec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            predicates.add(cb.isNull(root.get("deletedAt")));
            if (siteId != null) {
                predicates.add(cb.equal(root.get("id"), siteId));
            } else {
                allowedSiteIds.ifPresent(ids -> predicates.add(root.get("id").in(ids)));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        List<Site> sites = siteRepository.findAll(siteSpec);

        OffsetDateTime reportedAt = OffsetDateTime.now();

        List<SitePresenceEntry> entries = sites.stream().map(site -> {
            // "Today" resolved per-site in that site's own timezone (audit 2026-08-04) — a
            // hardcoded JVM-default/UTC "today" was wrong for the exact hours it mattered most
            // (shift start/end near a UTC day boundary), and also bounds which open sessions
            // count as "currently checked in": a session opened days ago and never checked out
            // must not still count as present today (same guard as the dashboard fix).
            java.time.ZoneId siteZone = java.time.ZoneId.of(
                    site.getTimezone() != null ? site.getTimezone() : "UTC");
            LocalDate today = LocalDate.now(siteZone);
            OffsetDateTime startOfToday = today.atStartOfDay(siteZone).toOffsetDateTime();

            // Employees currently checked in (open sessions opened today)
            List<CheckinRecord> openSessions = checkinRepository
                    .findOpenSessionsBySite(tenantId, site.getId(), startOfToday);
            Set<UUID> presentIds = openSessions.stream()
                    .map(CheckinRecord::getEmployeeId)
                    .collect(Collectors.toSet());

            // Employees with active assignments today
            List<Assignment> activeAssignments = assignmentRepository
                    .findActiveAssignmentsForSiteOnDate(tenantId, site.getId(), today,
                            DayOfWeekBitmask.bitForDate(today));
            Set<UUID> assignedIds = activeAssignments.stream()
                    .map(Assignment::getEmployeeId)
                    .collect(Collectors.toSet());

            List<UUID> absentIdList  = assignedIds.stream()
                    .filter(id -> !presentIds.contains(id))
                    .collect(Collectors.toList());

            return SitePresenceEntry.builder()
                    .siteId(site.getId())
                    .siteName(site.getName())
                    .timezone(site.getTimezone())
                    .assignedCount(assignedIds.size())
                    .presentCount(presentIds.size())
                    .absentCount(absentIdList.size())
                    .presentEmployees(toEmployeeRefs(tenantId, new ArrayList<>(presentIds)))
                    .absentEmployees(toEmployeeRefs(tenantId, absentIdList))
                    .build();
        }).collect(Collectors.toList());

        int totalSites    = entries.size();
        int totalPresent  = entries.stream().mapToInt(SitePresenceEntry::getPresentCount).sum();
        int totalAssigned = entries.stream().mapToInt(SitePresenceEntry::getAssignedCount).sum();
        int totalAbsent   = entries.stream().mapToInt(SitePresenceEntry::getAbsentCount).sum();

        // Manual pagination
        int total   = entries.size();
        int fromIdx = Math.min(page * size, total);
        int toIdx   = Math.min(fromIdx + size, total);
        List<SitePresenceEntry> pageContent = entries.subList(fromIdx, toIdx);
        PageRequest pageable = PageRequest.of(page, size);
        PageResponse<SitePresenceEntry> sitePage =
                PageResponse.from(new PageImpl<>(pageContent, pageable, total));

        log.info("Site presence report: tenantId={} siteId={} totalSites={} present={} assigned={}",
                tenantId, siteId, totalSites, totalPresent, totalAssigned);

        return SitePresenceReportResponse.builder()
                .reportedAt(reportedAt)
                .totalSites(totalSites)
                .totalPresent(totalPresent)
                .totalAssigned(totalAssigned)
                .totalAbsent(totalAbsent)
                .sites(sitePage)
                .build();
    }

    @Transactional(readOnly = true)
    public byte[] exportViolations(UUID tenantId, LocalDate from, LocalDate to,
                                   UUID siteId, UUID employeeId, String violationType, Boolean resolved,
                                   UUID workspaceId,
                                   UUID callerUserId, boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("reports:export")) {
                throw new AccessDeniedException("reports:export permission required to export violations");
            }
        }

        // #125 (2026-08-18): workspace filter, same resolution as getViolationReport.
        Set<UUID> workspaceEmployeeIds = resolveWorkspaceEmployeeIds(tenantId, workspaceId);

        List<Violation> all;
        if (workspaceEmployeeIds != null && workspaceEmployeeIds.isEmpty()) {
            all = List.of();
        } else {
            try {
                UUID effectiveSiteId =
                        resolveSiteFilterForReports(callerUserId, tenantId, siteId, callerIsPlatformAdmin);
                org.springframework.data.jpa.domain.Specification<Violation> spec = ViolationSpecification.build(
                        tenantId, employeeId, effectiveSiteId, violationType, resolved, from, to,
                        null, null, workspaceEmployeeIds);
                all = violationRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "checkDate"));
            } catch (NoSitesAllowedForReport e) {
                // Caller is site-restricted but has no allowed sites left — correct result is an
                // empty export, not "no site filter" (which would leak every site's violations).
                all = List.of();
            }
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Violations");

            String[] headers = {
                "ID", "Employee ID", "Site ID", "Violation Type",
                "Check Date", "Description", "Resolved", "Resolved At",
                "Affects Attendance", "Employee Note", "Created At"
            };
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }

            int rowNum = 1;
            for (Violation v : all) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(v.getId().toString());
                row.createCell(1).setCellValue(v.getEmployeeId().toString());
                row.createCell(2).setCellValue(v.getSiteId() != null ? v.getSiteId().toString() : "");
                row.createCell(3).setCellValue(v.getViolationType());
                row.createCell(4).setCellValue(v.getCheckDate() != null ? v.getCheckDate().toString() : "");
                row.createCell(5).setCellValue(v.getDescription() != null ? v.getDescription() : "");
                row.createCell(6).setCellValue(v.isResolved());
                row.createCell(7).setCellValue(v.getResolvedAt() != null ? v.getResolvedAt().toString() : "");
                row.createCell(8).setCellValue(v.isAffectsAttendance());
                row.createCell(9).setCellValue(v.getEmployeeNote() != null ? v.getEmployeeNote() : "");
                row.createCell(10).setCellValue(v.getCreatedAt() != null ? v.getCreatedAt().toString() : "");
            }

            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            log.info("Violation export: tenantId={} from={} to={} siteId={} employeeId={} resolved={} rows={}",
                    tenantId, from, to, siteId, employeeId, resolved, all.size());
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate violations Excel export", e);
        }
    }

    @Transactional(readOnly = true)
    public FaceIdReportResponse getFaceIdEnrollmentReport(
            UUID tenantId, String statusFilter, UUID departmentId, String search, int page, int size,
            UUID callerUserId, boolean callerIsPlatformAdmin) {

        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("reports:list")) {
                throw new AccessDeniedException("reports:list permission required to view Face ID report");
            }
        }

        // Load all active employees for the tenant — departmentId and search (name/email/code)
        // are applied server-side (added 2026-08-05 per FE feedback) rather than forcing FE to
        // fetch the full roster and filter client-side, same reasoning as global search.
        String searchTerm = search == null ? null : search.trim();
        String searchPattern = (searchTerm != null && !searchTerm.isEmpty())
                ? "%" + searchTerm.toLowerCase() + "%" : null;
        Specification<Employee> empSpec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>(List.of(
                    cb.equal(root.get("tenantId"), tenantId),
                    cb.isNull(root.get("deletedAt"))));
            if (departmentId != null) {
                predicates.add(cb.equal(root.get("departmentId"), departmentId));
            }
            if (searchPattern != null) {
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("firstName")), searchPattern),
                        cb.like(cb.lower(root.get("lastName")), searchPattern),
                        cb.like(cb.lower(root.get("email")), searchPattern),
                        cb.like(cb.lower(root.get("employeeCode")), searchPattern)));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        List<Employee> employees = employeeRepository.findAll(empSpec,
                Sort.by(Sort.Direction.ASC, "lastName", "firstName"));

        // Site-scope: a caller restricted to specific sites (e.g. SITE_SUPERVISOR) must not see
        // Face ID status for employees outside their allowed sites — same treatment as every
        // other employee-facing list in this system. Employee itself carries no direct site
        // column, so scope via assignment linkage like EmployeeService does.
        java.util.Optional<Set<UUID>> allowedSiteIds =
                siteScopeService.resolveAllowedSiteIds(callerUserId, tenantId, callerIsPlatformAdmin);
        if (allowedSiteIds.isPresent()) {
            if (allowedSiteIds.get().isEmpty()) {
                employees = List.of();
            } else {
                Set<UUID> visibleEmployeeIds = assignmentRepository
                        .findDistinctEmployeeIdsByTenantIdAndSiteIdIn(tenantId, allowedSiteIds.get());
                employees = employees.stream()
                        .filter(e -> visibleEmployeeIds.contains(e.getId()))
                        .collect(Collectors.toList());
            }
        }

        // Build map: employeeId → FaceProfile
        Map<UUID, FaceProfile> profileMap = faceProfileRepository.findAllByTenantId(tenantId)
                .stream().collect(Collectors.toMap(FaceProfile::getEmployeeId, fp -> fp));

        // Build full row list
        List<FaceIdReportRow> allRows = employees.stream().map(emp -> {
            FaceProfile fp = profileMap.get(emp.getId());
            String status = (fp == null) ? "not_enrolled" : fp.getStatus();
            return FaceIdReportRow.builder()
                    .employeeId(emp.getId())
                    .employeeCode(emp.getEmployeeCode())
                    .firstName(emp.getFirstName())
                    .lastName(emp.getLastName())
                    .email(emp.getEmail())
                    .department(emp.getDepartment())
                    .faceIdStatus(status)
                    .consentGiven(fp != null && fp.isConsentGiven())
                    .consentGivenAt(fp != null ? fp.getConsentGivenAt() : null)
                    .enrolledAt(fp != null ? fp.getEnrolledAt() : null)
                    .revokedAt(fp != null ? fp.getRevokedAt() : null)
                    .reviewStatus(fp != null ? fp.getReviewStatus() : "none")
                    .submittedAt(fp != null ? fp.getSubmittedAt() : null)
                    .rejectionReason(fp != null ? fp.getRejectionReason() : null)
                    .build();
        }).collect(Collectors.toList());

        // Aggregate counts across all rows (before status filter). "pending" here means
        // reviewStatus=pending (an in-flight submission awaiting HR decision) — independent of
        // faceIdStatus, since a re-enrollment can be pending while faceIdStatus is still
        // "enrolled" (the old approved face keeps working during review).
        int enrolledCount    = (int) allRows.stream().filter(r -> "enrolled".equals(r.getFaceIdStatus())).count();
        int pendingCount     = (int) allRows.stream().filter(r -> "pending".equals(r.getReviewStatus())).count();
        int notEnrolledCount = (int) allRows.stream().filter(r -> "not_enrolled".equals(r.getFaceIdStatus())).count();
        int revokedCount     = (int) allRows.stream().filter(r -> "revoked".equals(r.getFaceIdStatus())).count();

        // Apply optional status filter — accepts either a faceIdStatus value or "pending" (mapped
        // to reviewStatus=pending, for HR filtering down to the review queue from this same report).
        List<FaceIdReportRow> filtered;
        if (statusFilter == null || statusFilter.isBlank()) {
            filtered = allRows;
        } else if ("pending".equals(statusFilter)) {
            filtered = allRows.stream().filter(r -> "pending".equals(r.getReviewStatus())).collect(Collectors.toList());
        } else {
            filtered = allRows.stream().filter(r -> statusFilter.equals(r.getFaceIdStatus())).collect(Collectors.toList());
        }

        // Manual pagination
        int total   = filtered.size();
        int fromIdx = Math.min(page * size, total);
        int toIdx   = Math.min(fromIdx + size, total);
        List<FaceIdReportRow> pageContent = filtered.subList(fromIdx, toIdx);
        PageRequest pageable = PageRequest.of(page, size);
        PageResponse<FaceIdReportRow> recordPage =
                PageResponse.from(new PageImpl<>(pageContent, pageable, total));

        log.info("Face ID enrollment report: tenantId={} total={} enrolled={} pending={} notEnrolled={} revoked={}",
                tenantId, employees.size(), enrolledCount, pendingCount, notEnrolledCount, revokedCount);

        return FaceIdReportResponse.builder()
                .totalEmployees(employees.size())
                .enrolledCount(enrolledCount)
                .pendingCount(pendingCount)
                .notEnrolledCount(notEnrolledCount)
                .revokedCount(revokedCount)
                .statusFilter(statusFilter)
                .departmentId(departmentId)
                .search(searchTerm)
                .records(recordPage)
                .build();
    }

    private ViolationListResponse toViolationListResponse(Violation v) {
        return ViolationListResponse.builder()
                .id(v.getId())
                .employeeId(v.getEmployeeId())
                .siteId(v.getSiteId())
                .violationType(v.getViolationType())
                .checkDate(v.getCheckDate())
                .description(v.getDescription())
                .resolved(v.isResolved())
                .resolvedAt(v.getResolvedAt())
                .resolution(v.getResolution())
                .employeeNote(v.getEmployeeNote())
                .employeePhotoUrl(v.getEmployeePhotoUrl())
                .affectsAttendance(v.isAffectsAttendance())
                .createdAt(v.getCreatedAt())
                .build();
    }

    /** Batch-resolves employee id/name/code for report rows that list many employees by ID
     *  (site presence, daily-report absent list) — added 2026-08-05 per FE feedback that forced
     *  a client-side batch lookup for every ID in these lists. 1 query regardless of list size,
     *  same batching approach already used for checkin/scheduled-check list responses. */
    private List<EmployeeRef> toEmployeeRefs(UUID tenantId, List<UUID> employeeIds) {
        if (employeeIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Employee> byId = employeeRepository
                .findAllByTenantIdAndIdInAndDeletedAtIsNull(tenantId, new java.util.HashSet<>(employeeIds))
                .stream().collect(Collectors.toMap(Employee::getId, e -> e));
        return employeeIds.stream()
                .map(id -> {
                    Employee e = byId.get(id);
                    return EmployeeRef.builder()
                            .employeeId(id)
                            .employeeName(e != null ? (e.getFirstName() + " " + e.getLastName()).trim() : null)
                            .employeeCode(e != null ? e.getEmployeeCode() : null)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private AttendanceSummaryResponse toResponse(AttendanceSummary a) {
        return AttendanceSummaryResponse.builder()
                .id(a.getId())
                .tenantId(a.getTenantId())
                .employeeId(a.getEmployeeId())
                .siteId(a.getSiteId())
                .shiftId(a.getShiftId())
                .assignmentId(a.getAssignmentId())
                .attendanceDate(a.getAttendanceDate())
                .firstCheckinAt(a.getFirstCheckinAt())
                .lastCheckoutAt(a.getLastCheckoutAt())
                .totalWorkMinutes(a.getTotalWorkMinutes())
                .sessionCount(a.getSessionCount())
                .status(a.getStatus())
                .late(a.isLate())
                .lateMinutes(a.getLateMinutes())
                .earlyLeave(a.isEarlyLeave())
                .earlyLeaveMinutes(a.getEarlyLeaveMinutes())
                .otMinutes(a.getOtMinutes())
                .missingCheckout(a.isMissingCheckout())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .adjustmentReason(a.getAdjustmentReason())
                .build();
    }
}

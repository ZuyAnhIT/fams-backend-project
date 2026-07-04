package com.fams.modules.report.service;

import com.fams.modules.assignment.entity.Assignment;
import com.fams.modules.assignment.repository.AssignmentRepository;
import com.fams.modules.attendance.dto.response.AttendanceSummaryResponse;
import com.fams.modules.attendance.entity.AttendanceSummary;
import com.fams.modules.attendance.repository.AttendanceSummaryRepository;
import com.fams.modules.attendance.specification.AttendanceSummarySpecification;
import com.fams.modules.rbac.repository.UserRoleRepository;
import com.fams.modules.attendance.dto.response.AttendanceHrMonthlyResponse;
import com.fams.modules.report.dto.response.DailyAttendanceReportResponse;
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
import java.util.LinkedHashMap;
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

    public ReportService(AttendanceSummaryRepository summaryRepository,
                         AssignmentRepository assignmentRepository,
                         ViolationRepository violationRepository,
                         SiteRepository siteRepository,
                         CheckinRepository checkinRepository,
                         UserRoleRepository userRoleRepository) {
        this.summaryRepository = summaryRepository;
        this.assignmentRepository = assignmentRepository;
        this.violationRepository = violationRepository;
        this.siteRepository = siteRepository;
        this.checkinRepository = checkinRepository;
        this.userRoleRepository = userRoleRepository;
    }

    @Transactional(readOnly = true)
    public DailyAttendanceReportResponse getDailyAttendanceReport(
            UUID tenantId, LocalDate date, UUID siteId, int page, int size,
            UUID callerUserId, boolean callerIsPlatformAdmin) {

        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("reports:list")) {
                throw new AccessDeniedException("reports:list permission required to view attendance reports");
            }
        }

        Specification<AttendanceSummary> spec =
                AttendanceSummarySpecification.build(tenantId, null, siteId, null, date, date);

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

        List<Assignment> activeAssignments = siteId != null
                ? assignmentRepository.findActiveAssignmentsForSiteOnDate(tenantId, siteId, date)
                : assignmentRepository.findActiveAssignmentsWithShiftForDate(tenantId, date);

        List<UUID> absentEmployeeIds = activeAssignments.stream()
                .map(Assignment::getEmployeeId)
                .filter(id -> !presentEmployeeIds.contains(id))
                .distinct()
                .collect(Collectors.toList());

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
                .absentEmployeeIds(absentEmployeeIds)
                .records(PageResponse.from(recordPage))
                .build();
    }

    @Transactional(readOnly = true)
    public MonthlyAttendanceReportResponse getMonthlyAttendanceReport(
            UUID tenantId, int year, int month, UUID siteId, int page, int size,
            UUID callerUserId, boolean callerIsPlatformAdmin) {

        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("reports:list")) {
                throw new AccessDeniedException("reports:list permission required to view attendance reports");
            }
        }

        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to   = from.plusMonths(1).minusDays(1);

        Specification<AttendanceSummary> spec =
                AttendanceSummarySpecification.build(tenantId, null, siteId, null, from, to);

        List<AttendanceSummary> allForMonth = summaryRepository.findAll(spec);

        // Group by employeeId+siteId to build per-employee monthly aggregates
        Map<String, List<AttendanceSummary>> grouped = new LinkedHashMap<>();
        for (AttendanceSummary row : allForMonth) {
            String key = row.getEmployeeId() + ":" + row.getSiteId();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<AttendanceHrMonthlyResponse> aggregated = grouped.values().stream()
                .map(group -> {
                    AttendanceSummary first = group.get(0);
                    return AttendanceHrMonthlyResponse.builder()
                            .tenantId(tenantId)
                            .employeeId(first.getEmployeeId())
                            .siteId(first.getSiteId())
                            .year(year)
                            .month(month)
                            .presentDays(group.size())
                            .totalWorkMinutes(group.stream().mapToInt(AttendanceSummary::getTotalWorkMinutes).sum())
                            .lateDays((int) group.stream().filter(AttendanceSummary::isLate).count())
                            .totalLateMinutes(group.stream().mapToInt(AttendanceSummary::getLateMinutes).sum())
                            .earlyLeaveDays((int) group.stream().filter(AttendanceSummary::isEarlyLeave).count())
                            .totalEarlyLeaveMinutes(group.stream().mapToInt(AttendanceSummary::getEarlyLeaveMinutes).sum())
                            .totalOtMinutes(group.stream().mapToInt(AttendanceSummary::getOtMinutes).sum())
                            .missingCheckoutDays((int) group.stream().filter(AttendanceSummary::isMissingCheckout).count())
                            .build();
                })
                .collect(Collectors.toList());

        // Tenant-wide totals
        int totalEmployees        = aggregated.size();
        int totalPresentDays      = aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getPresentDays).sum();
        int totalWorkMinutes      = aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getTotalWorkMinutes).sum();
        int totalLateDays         = aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getLateDays).sum();
        int totalLateMinutes      = aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getTotalLateMinutes).sum();
        int totalEarlyLeaveDays   = aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getEarlyLeaveDays).sum();
        int totalEarlyLeaveMinutes = aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getTotalEarlyLeaveMinutes).sum();
        int totalMissingCheckoutDays = aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getMissingCheckoutDays).sum();
        int totalOtMinutes        = aggregated.stream().mapToInt(AttendanceHrMonthlyResponse::getTotalOtMinutes).sum();

        // Manual pagination over aggregated list
        int total    = aggregated.size();
        int fromIdx  = Math.min(page * size, total);
        int toIdx    = Math.min(fromIdx + size, total);
        List<AttendanceHrMonthlyResponse> pageContent = aggregated.subList(fromIdx, toIdx);
        PageRequest pageable = PageRequest.of(page, size);
        PageResponse<AttendanceHrMonthlyResponse> recordPage =
                PageResponse.from(new PageImpl<>(pageContent, pageable, total));

        log.info("Monthly attendance report: tenantId={} {}/{} siteId={} employees={} presentDays={}",
                tenantId, year, month, siteId, totalEmployees, totalPresentDays);

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
                .records(recordPage)
                .build();
    }

    @Transactional(readOnly = true)
    public byte[] exportMonthlyAttendance(UUID tenantId, int year, int month, UUID siteId,
                                          UUID callerUserId, boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("reports:export")) {
                throw new AccessDeniedException("reports:export permission required to export attendance data");
            }
        }

        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to   = from.plusMonths(1).minusDays(1);

        Specification<AttendanceSummary> spec =
                AttendanceSummarySpecification.build(tenantId, null, siteId, null, from, to);
        List<AttendanceSummary> allForMonth = summaryRepository.findAll(spec);

        Map<String, List<AttendanceSummary>> grouped = new LinkedHashMap<>();
        for (AttendanceSummary row : allForMonth) {
            String key = row.getEmployeeId() + ":" + row.getSiteId();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<AttendanceHrMonthlyResponse> aggregated = grouped.values().stream()
                .map(group -> {
                    AttendanceSummary first = group.get(0);
                    return AttendanceHrMonthlyResponse.builder()
                            .tenantId(tenantId)
                            .employeeId(first.getEmployeeId())
                            .siteId(first.getSiteId())
                            .year(year)
                            .month(month)
                            .presentDays(group.size())
                            .totalWorkMinutes(group.stream().mapToInt(AttendanceSummary::getTotalWorkMinutes).sum())
                            .lateDays((int) group.stream().filter(AttendanceSummary::isLate).count())
                            .totalLateMinutes(group.stream().mapToInt(AttendanceSummary::getLateMinutes).sum())
                            .earlyLeaveDays((int) group.stream().filter(AttendanceSummary::isEarlyLeave).count())
                            .totalEarlyLeaveMinutes(group.stream().mapToInt(AttendanceSummary::getEarlyLeaveMinutes).sum())
                            .totalOtMinutes(group.stream().mapToInt(AttendanceSummary::getOtMinutes).sum())
                            .missingCheckoutDays((int) group.stream().filter(AttendanceSummary::isMissingCheckout).count())
                            .build();
                })
                .collect(Collectors.toList());

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(
                    String.format("Attendance %d-%02d", year, month));

            String[] headers = {
                "Employee ID", "Site ID", "Year", "Month",
                "Present Days", "Work Minutes",
                "Late Days", "Late Minutes",
                "Early Leave Days", "Early Leave Minutes",
                "OT Minutes", "Missing Checkout Days"
            };
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }

            int rowNum = 1;
            for (AttendanceHrMonthlyResponse rec : aggregated) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(rec.getEmployeeId().toString());
                row.createCell(1).setCellValue(rec.getSiteId().toString());
                row.createCell(2).setCellValue(rec.getYear());
                row.createCell(3).setCellValue(rec.getMonth());
                row.createCell(4).setCellValue(rec.getPresentDays());
                row.createCell(5).setCellValue(rec.getTotalWorkMinutes());
                row.createCell(6).setCellValue(rec.getLateDays());
                row.createCell(7).setCellValue(rec.getTotalLateMinutes());
                row.createCell(8).setCellValue(rec.getEarlyLeaveDays());
                row.createCell(9).setCellValue(rec.getTotalEarlyLeaveMinutes());
                row.createCell(10).setCellValue(rec.getTotalOtMinutes());
                row.createCell(11).setCellValue(rec.getMissingCheckoutDays());
            }

            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            log.info("Attendance export: tenantId={} {}/{} siteId={} rows={}",
                    tenantId, year, month, siteId, aggregated.size());
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate Excel export", e);
        }
    }

    @Transactional(readOnly = true)
    public ViolationReportResponse getViolationReport(
            UUID tenantId, LocalDate from, LocalDate to,
            UUID siteId, UUID employeeId, String violationType,
            int page, int size,
            UUID callerUserId, boolean callerIsPlatformAdmin) {

        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("reports:list")) {
                throw new AccessDeniedException("reports:list permission required to view violation reports");
            }
        }

        org.springframework.data.jpa.domain.Specification<Violation> spec =
                ViolationSpecification.build(tenantId, employeeId, siteId, violationType, null, from, to);

        // Load all matching violations for aggregate stats
        List<Violation> all = violationRepository.findAll(spec);

        int totalViolations      = all.size();
        int resolvedCount        = (int) all.stream().filter(Violation::isResolved).count();
        int unresolvedCount      = totalViolations - resolvedCount;
        int affectsAttendanceCount = (int) all.stream().filter(Violation::isAffectsAttendance).count();

        Map<String, Long> byViolationType = all.stream()
                .collect(Collectors.groupingBy(Violation::getViolationType, Collectors.counting()));

        Map<String, Long> bySite = all.stream()
                .collect(Collectors.groupingBy(v -> v.getSiteId().toString(), Collectors.counting()));

        Map<String, Long> byEmployee = all.stream()
                .collect(Collectors.groupingBy(v -> v.getEmployeeId().toString(), Collectors.counting()));

        // Paginated records
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "checkDate"));
        Page<ViolationListResponse> recordPage = violationRepository.findAll(spec, pageable)
                .map(this::toViolationListResponse);

        log.info("Violation report: tenantId={} from={} to={} siteId={} employeeId={} total={}",
                tenantId, from, to, siteId, employeeId, totalViolations);

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

        // Fetch active sites for this tenant (optionally filtered to one site)
        org.springframework.data.jpa.domain.Specification<Site> siteSpec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            predicates.add(cb.isNull(root.get("deletedAt")));
            if (siteId != null) {
                predicates.add(cb.equal(root.get("id"), siteId));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        List<Site> sites = siteRepository.findAll(siteSpec);

        LocalDate today = LocalDate.now();
        OffsetDateTime reportedAt = OffsetDateTime.now();

        List<SitePresenceEntry> entries = sites.stream().map(site -> {
            // Employees currently checked in (open sessions)
            List<CheckinRecord> openSessions = checkinRepository
                    .findOpenSessionsBySite(tenantId, site.getId());
            Set<UUID> presentIds = openSessions.stream()
                    .map(CheckinRecord::getEmployeeId)
                    .collect(Collectors.toSet());

            // Employees with active assignments today
            List<Assignment> activeAssignments = assignmentRepository
                    .findActiveAssignmentsForSiteOnDate(tenantId, site.getId(), today);
            Set<UUID> assignedIds = activeAssignments.stream()
                    .map(Assignment::getEmployeeId)
                    .collect(Collectors.toSet());

            List<UUID> presentList = new ArrayList<>(presentIds);
            List<UUID> absentList  = assignedIds.stream()
                    .filter(id -> !presentIds.contains(id))
                    .collect(Collectors.toList());

            return SitePresenceEntry.builder()
                    .siteId(site.getId())
                    .siteName(site.getName())
                    .timezone(site.getTimezone())
                    .assignedCount(assignedIds.size())
                    .presentCount(presentIds.size())
                    .absentCount(absentList.size())
                    .presentEmployeeIds(presentList)
                    .absentEmployeeIds(absentList)
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
                                   UUID siteId, UUID employeeId, String violationType,
                                   UUID callerUserId, boolean callerIsPlatformAdmin) {
        if (!callerIsPlatformAdmin) {
            Set<String> perms = userRoleRepository
                    .findPermissionNamesByUserIdAndTenantId(callerUserId, tenantId);
            if (!perms.contains("reports:export")) {
                throw new AccessDeniedException("reports:export permission required to export violations");
            }
        }

        org.springframework.data.jpa.domain.Specification<Violation> spec =
                ViolationSpecification.build(tenantId, employeeId, siteId, violationType, null, from, to);
        List<Violation> all = violationRepository.findAll(spec,
                Sort.by(Sort.Direction.DESC, "checkDate"));

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
            log.info("Violation export: tenantId={} from={} to={} siteId={} employeeId={} rows={}",
                    tenantId, from, to, siteId, employeeId, all.size());
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate violations Excel export", e);
        }
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
                .employeeNote(v.getEmployeeNote())
                .employeePhotoUrl(v.getEmployeePhotoUrl())
                .createdAt(v.getCreatedAt())
                .build();
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

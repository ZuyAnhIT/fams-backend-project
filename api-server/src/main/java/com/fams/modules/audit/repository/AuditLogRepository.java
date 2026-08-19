package com.fams.modules.audit.repository;

import com.fams.modules.audit.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository
    extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {

  @Query("""
      SELECT a FROM AuditLog a
      WHERE a.requestId = :requestId
      ORDER BY a.createdAt ASC
      """)
  List<AuditLog> findByRequestId(@Param("requestId") String requestId);

  /** #135 (2026-08-19): export-limit enforcement usage counter — every export action already
   *  writes an EXPORT_* audit entry (EXPORT_ATTENDANCE, EXPORT_VIOLATIONS,
   *  EXPORT_FACE_ID_NOT_ENROLLED), so reusing that as the usage source avoids a separate counter
   *  table just for this. */
  @Query("""
      SELECT COUNT(a) FROM AuditLog a
      WHERE a.tenantId = :tenantId AND a.action LIKE 'EXPORT\\_%' ESCAPE '\\'
        AND a.createdAt >= :from AND a.createdAt < :to
      """)
  long countExportsByTenantInRange(@Param("tenantId") UUID tenantId,
                                    @Param("from") OffsetDateTime from,
                                    @Param("to") OffsetDateTime to);
}

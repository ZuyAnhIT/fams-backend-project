package com.fams.modules.audit.repository;

import com.fams.modules.audit.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}

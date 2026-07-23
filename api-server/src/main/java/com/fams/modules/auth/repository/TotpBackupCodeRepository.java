package com.fams.modules.auth.repository;

import com.fams.modules.auth.entity.TotpBackupCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TotpBackupCodeRepository extends JpaRepository<TotpBackupCode, UUID> {

    List<TotpBackupCode> findByUserIdAndUsedAtIsNull(UUID userId);

    void deleteByUserId(UUID userId);
}

package com.fams.modules.auth.repository;

import com.fams.modules.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String hash);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = :now WHERE rt.user.id = :userId AND rt.revokedAt IS NULL")
    void revokeAllActiveByUserId(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    // Issue #6 (docs/issues/ISSUES.md): backs "list my sessions" and "revoke one specific session".

    @Query("SELECT rt FROM RefreshToken rt WHERE rt.user.id = :userId AND rt.revokedAt IS NULL " +
           "AND rt.expiresAt > :now ORDER BY rt.lastUsedAt DESC")
    List<RefreshToken> findAllActiveByUserId(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    Optional<RefreshToken> findByIdAndUserIdAndRevokedAtIsNull(UUID id, UUID userId);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = :now WHERE rt.user.id = :userId " +
           "AND rt.revokedAt IS NULL AND rt.deviceId <> :currentDeviceId")
    void revokeAllActiveByUserIdExceptDevice(@Param("userId") UUID userId, @Param("now") OffsetDateTime now,
                                              @Param("currentDeviceId") String currentDeviceId);
}

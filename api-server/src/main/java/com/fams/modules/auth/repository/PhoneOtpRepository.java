package com.fams.modules.auth.repository;

import com.fams.modules.auth.entity.PhoneOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PhoneOtpRepository extends JpaRepository<PhoneOtp, UUID> {

    /**
     * Tìm OTP hợp lệ mới nhất cho phone + purpose:
     * chưa dùng, chưa hết hạn, ít hơn 5 lần sai.
     */
    @Query("""
        SELECT o FROM PhoneOtp o
        WHERE o.phone = :phone
          AND o.purpose = :purpose
          AND o.usedAt IS NULL
          AND o.expiresAt > :now
          AND o.attempts < 5
        ORDER BY o.createdAt DESC
        LIMIT 1
        """)
    Optional<PhoneOtp> findValidOtp(
            @Param("phone") String phone,
            @Param("purpose") String purpose,
            @Param("now") OffsetDateTime now);

    /**
     * Đếm số OTP đã gửi trong khoảng thời gian (rate-limit gửi lại).
     */
    @Query("""
        SELECT COUNT(o) FROM PhoneOtp o
        WHERE o.phone = :phone
          AND o.purpose = :purpose
          AND o.createdAt > :since
        """)
    long countRecentByPhone(
            @Param("phone") String phone,
            @Param("purpose") String purpose,
            @Param("since") OffsetDateTime since);

    /**
     * Huỷ tất cả OTP cũ chưa dùng của phone+purpose khi gửi OTP mới.
     */
    @Modifying
    @Query("""
        UPDATE PhoneOtp o SET o.usedAt = :now
        WHERE o.phone = :phone
          AND o.purpose = :purpose
          AND o.usedAt IS NULL
        """)
    void invalidateAllActive(
            @Param("phone") String phone,
            @Param("purpose") String purpose,
            @Param("now") OffsetDateTime now);
}
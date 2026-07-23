package com.fams.modules.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "phone_otps")
public class PhoneOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(nullable = false, length = 20)
    private String phone;

    /** Mã OTP 6 số — lưu plain text, hết hạn sau 5 phút */
    @Column(name = "otp_code", nullable = false, length = 10)
    private String otpCode;

    /** REGISTER | LOGIN | VERIFY */
    @Column(nullable = false, length = 50)
    @Builder.Default
    private String purpose = "REGISTER";

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    /** null = chưa dùng */
    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    /** Số lần nhập sai — block sau 5 lần */
    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public boolean isExpired() {
        return OffsetDateTime.now().isAfter(expiresAt);
    }

    public boolean isUsed() {
        return usedAt != null;
    }
}
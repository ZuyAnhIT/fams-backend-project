-- V65: Bảng lưu OTP xác thực số điện thoại khi đăng ký
-- Dùng cho flow: đăng ký phone → backend gửi OTP qua Firebase → user nhập OTP → verify
-- OTP hết hạn sau 5 phút, chỉ dùng được 1 lần

CREATE TABLE phone_otps (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    phone         VARCHAR(20)  NOT NULL,
    otp_code      VARCHAR(10)  NOT NULL,          -- mã 6 số (hash hoặc plain tùy policy)
    purpose       VARCHAR(50)  NOT NULL DEFAULT 'REGISTER', -- REGISTER | LOGIN | VERIFY
    expires_at    TIMESTAMPTZ  NOT NULL,
    used_at       TIMESTAMPTZ,                    -- null = chưa dùng
    attempts      INT          NOT NULL DEFAULT 0, -- số lần nhập sai
    ip_address    VARCHAR(45),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_phone_otps_phone_purpose ON phone_otps(phone, purpose)
    WHERE used_at IS NULL;
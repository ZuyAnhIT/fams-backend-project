-- Backlog #1 (Đăng nhập email/mật khẩu, docs/BACKLOG.md): Acceptance Criteria requires
-- recording last_login on successful login.
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ;

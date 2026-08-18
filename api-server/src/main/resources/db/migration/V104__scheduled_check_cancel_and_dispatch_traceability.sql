-- #99 (2026-08-18): "Hủy scheduled check" AC yêu cầu lưu ai hủy/lúc nào/vì sao, nhưng entity
-- trước đó hoàn toàn không có các field này (không phải chỉ quên set, thật sự chưa có cột).
ALTER TABLE scheduled_checks
    ADD COLUMN cancelled_by     UUID REFERENCES users(id),
    ADD COLUMN cancelled_at     TIMESTAMPTZ,
    ADD COLUMN cancelled_reason TEXT;

-- #100 (2026-08-18): "Gửi random check notification" AC yêu cầu set sent_at + notification_id,
-- nhưng entity chỉ có status chuyển 'sent' mà không có timestamp/tham chiếu notification thật.
ALTER TABLE scheduled_checks
    ADD COLUMN sent_at         TIMESTAMPTZ,
    ADD COLUMN notification_id UUID REFERENCES notifications(id);

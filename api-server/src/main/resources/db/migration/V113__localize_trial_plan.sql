-- Keep the public plan catalogue consistently localized for Vietnamese customers.
UPDATE plans
SET display_name = 'Dùng thử',
    description = 'Trải nghiệm miễn phí các tính năng cơ bản trước khi chọn gói trả phí.',
    updated_at = now()
WHERE name = 'trial' AND deleted_at IS NULL;

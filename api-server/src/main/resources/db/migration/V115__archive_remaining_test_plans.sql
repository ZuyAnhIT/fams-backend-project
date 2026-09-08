-- V114 archived the first group of disposable subscription-test plans. The migration test also
-- creates target variants with an extra size/state segment in their keys; archive those too.
UPDATE plans
SET is_active = FALSE,
    deleted_at = COALESCE(deleted_at, now()),
    updated_at = now()
WHERE deleted_at IS NULL
  AND name ~ '^test-tgt-(small|big|inactive)-[0-9]+$';

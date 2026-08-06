# Audit Log API

Audit log viewer for PLATFORM_ADMIN and tenant-level admins (`TENANT_ADMIN`, `HR_MANAGER`, or any role granted `audit:list`/`audit:read`). Records actor, action, affected entity, old/new value diffs, and request correlation ID for security/compliance investigation.

Base path: `/api/v1/audit-logs`

> **Security update (2026-08-06):** this endpoint is now tenant-scoped for non-platform-admin callers. Previously any user with `audit:list`/`audit:read` could view another tenant's audit trail by passing a different `tenantId` — this has been fixed. See "Tenant scoping" below before integrating.

---

## Tenant scoping (read this first)

- **Platform Admin**: unrestricted. May omit `tenantId` to see every tenant's logs in one call, or pass any `tenantId` to filter.
- **Everyone else** (`TENANT_ADMIN`, `HR_MANAGER`, etc.): scoped to the tenant(s) they actually hold an active role in.
  - Pass a `tenantId` that isn't one of the caller's own tenants → **403**.
  - Omit `tenantId` and the caller belongs to exactly one tenant → automatically scoped to that tenant.
  - Omit `tenantId` and the caller belongs to **more than one tenant** (a real case — some users hold roles in multiple tenants simultaneously) → **403**, must pass `tenantId` explicitly to disambiguate.
- Fetching a single entry by ID (`GET /{id}`) that belongs to a tenant outside the caller's scope returns **404**, not 403 — consistent with "don't confirm existence of resources outside your access" (same convention as GitHub's private-repo 404s).
- Tracing by `requestId` (`GET ?requestId=...`) filters results to the caller's tenant scope — entries from other tenants are silently excluded from the returned list, never surfaced.

**FE implication:** if your admin UI is used by users who might hold roles in multiple tenants, always pass `tenantId` explicitly (e.g. from the tenant switcher / current workspace context) rather than relying on server-side inference — omitting it is only safe for single-tenant users and will 403 otherwise.

---

## `GET /api/v1/audit-logs`

Paginated list.

**Query params (all optional except pagination has defaults):**

| Param | Type | Notes |
|---|---|---|
| `tenantId` | UUID | See scoping rules above. |
| `actorId` | UUID | Filter by the user who performed the action. |
| `entityType` | string | e.g. `Employee`, `Site`. |
| `entityId` | string | |
| `action` | string | e.g. `CREATE`, `UPDATE`, `DELETE`. |
| `requestId` | string | If present, switches to trace mode (see below) — other list filters/pagination are ignored. |
| `from` / `to` | ISO-8601 datetime | Date range. |
| `page` | int | Default `0`. |
| `size` | int | Default `20`, max `200`. |

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": "…",
        "tenantId": "…",
        "actorId": "…",
        "actorEmail": "hr@acme.com",
        "entityType": "Employee",
        "entityId": "…",
        "action": "UPDATE",
        "oldValue": { "salary": 1000 },
        "newValue": { "salary": 1200 },
        "requestId": "req-abc123",
        "ipAddress": "203.0.113.5",
        "userAgent": "Mozilla/5.0 …",
        "createdAt": "2026-08-06T04:00:00Z"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "first": true,
    "last": true
  }
}
```

`401` if unauthenticated, `403` if the caller lacks `audit:list`/`PLATFORM_ADMIN`, or if tenant-scope is violated (see above).

### Trace mode (`requestId` param)

Passing `requestId` returns every audit entry sharing that request correlation ID — useful for reconstructing "everything that happened during this one API call" (e.g. a bulk operation that touched multiple entities). Response uses the same `PageResponse` shape but as a single unpaginated page containing all matches within the caller's tenant scope.

---

## `GET /api/v1/audit-logs/{id}`

Single entry, including full `oldValue`/`newValue` JSONB diffs for that action.

`404` if not found **or** if found but outside the caller's tenant scope (never distinguishes the two). `403` if the caller lacks `audit:read`/`PLATFORM_ADMIN`.

---

## FE integration notes

- Always surface `requestId` in any error-toast/support-flow copy where relevant — it's the fastest way for support/eng to trace a specific user action across the audit trail via the trace-mode query above.
- Treat `oldValue`/`newValue` as opaque JSON per `entityType` — don't hardcode field expectations, render generically (e.g. a key/value diff table) since different entity types will have different shapes.
- For a multi-tenant admin UI, always pass `tenantId` explicitly (see scoping section) to avoid unexpected 403s for users with roles in more than one tenant.

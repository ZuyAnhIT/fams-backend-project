# Saved Filters API

Personal saved filters for large list screens (violations, checkins, employees, reports...). A saved filter is **private to the user who created it** — never shared with other users in the same tenant, same trust model as personal notification settings. This matches how Jira/Linear/Gmail "saved views" work.

Base path: `/api/v1/tenants/{tenantId}/saved-filters`
Auth: any authenticated user (`isAuthenticated()`) — no special permission needed, endpoints are self-scoped to the caller by construction.

---

## Data model

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | |
| `resourceType` | string | Which list screen this applies to, e.g. `"violations"`, `"checkins"`, `"employees"`. Free-form string, not an enum — FE and backend must agree on the value used per screen. |
| `name` | string | Display name. Unique per `user + resourceType` (case-insensitive). |
| `filterParams` | object | Stored and returned **verbatim** — the backend never interprets its shape. FE is responsible for re-applying these exact key/value pairs as query params to the corresponding list endpoint. |
| `isDefault` | boolean | At most one default per `user + resourceType`. Setting `true` automatically clears the previous default, if any. |
| `createdAt` / `updatedAt` | ISO-8601 timestamp | |

---

## `GET /api/v1/tenants/{tenantId}/saved-filters?resourceType=violations`

Returns the caller's own saved filters for the given `resourceType`, sorted by name.

**Query params:** `resourceType` (required).

```json
{
  "success": true,
  "data": [
    {
      "id": "…",
      "resourceType": "violations",
      "name": "Vi phạm chưa xử lý tháng này",
      "filterParams": { "resolved": false, "violationType": "face_fail" },
      "isDefault": true,
      "createdAt": "2026-08-06T04:00:00Z",
      "updatedAt": "2026-08-06T04:00:00Z"
    }
  ]
}
```

401 if unauthenticated. Only ever returns filters owned by the calling user — there is no way to list another user's filters, even for admins.

---

## `POST /api/v1/tenants/{tenantId}/saved-filters`

```json
{
  "resourceType": "violations",
  "name": "Vi phạm chưa xử lý tháng này",
  "filterParams": { "resolved": false, "violationType": "face_fail" },
  "isDefault": false
}
```

- `resourceType`, `name`, `filterParams` required.
- Returns `201` with the created `SavedFilterResponse`.
- Returns `409` if the user already has a filter with this name for this `resourceType`.

---

## `PATCH /api/v1/tenants/{tenantId}/saved-filters/{filterId}`

Partial update — only send the fields you want to change.

```json
{ "name": "New name" }
```
```json
{ "isDefault": true }
```
```json
{ "filterParams": { "resolved": true } }
```

- `200` with the updated filter.
- `404` if the filter doesn't exist or isn't owned by the caller (same response for both cases — doesn't confirm existence of another user's filter).
- `409` if renaming to a name already used by another of the caller's filters for this `resourceType`.

---

## `DELETE /api/v1/tenants/{tenantId}/saved-filters/{filterId}`

Soft-deletes. `204` on success, `404` if not found/not owned.

---

## FE integration notes

- On list-screen load, call `GET .../saved-filters?resourceType=<screen>` and auto-apply the entry with `isDefault:true`, if any.
- When the user clicks "Save current filter", POST the exact query params currently applied to the list endpoint as `filterParams` — no client-side transformation needed, they'll come back exactly as sent.
- `resourceType` values are not currently enumerated/validated server-side — use a consistent string per screen (e.g. `"violations"`, `"checkins"`, `"employees"`, `"reports"`) and keep it stable, since changing it effectively creates a new filter namespace.

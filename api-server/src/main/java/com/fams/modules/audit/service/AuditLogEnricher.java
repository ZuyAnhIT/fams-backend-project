package com.fams.modules.audit.service;

import com.fams.modules.audit.entity.AuditLog;
import com.fams.modules.auth.repository.UserRepository;
import com.fams.modules.employee.repository.EmployeeRepository;
import com.fams.modules.rbac.repository.RoleRepository;
import com.fams.modules.shift.repository.ShiftRepository;
import com.fams.modules.site.repository.SiteRepository;
import com.fams.modules.tenant.repository.TenantRepository;
import com.fams.modules.workspace.repository.WorkspaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns the raw IDs on an audit log page into the human-readable names a real reviewer needs
 * (#audit-readability, 2026-09-03): who did it (actor display name, not just a UUID), and what
 * it was done to ("Nguyễn Văn An", "Công trình Quận 1" — not a UUID string).
 *
 * <p>Batch-loads once per entity type for a whole page (no N+1). For entity types without a
 * cheap name lookup it falls back to a display-ish field pulled from the change snapshot
 * ({@code newValue}/{@code oldValue}); {@code AccessControl} rows (whose entityId is a request
 * path, not an entity) get no name — the endpoint column already shows what matters there.
 */
@Slf4j
@Component
public class AuditLogEnricher {

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final SiteRepository siteRepository;
    private final WorkspaceRepository workspaceRepository;
    private final RoleRepository roleRepository;
    private final ShiftRepository shiftRepository;
    private final TenantRepository tenantRepository;

    public AuditLogEnricher(UserRepository userRepository, EmployeeRepository employeeRepository,
                            SiteRepository siteRepository, WorkspaceRepository workspaceRepository,
                            RoleRepository roleRepository, ShiftRepository shiftRepository,
                            TenantRepository tenantRepository) {
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
        this.siteRepository = siteRepository;
        this.workspaceRepository = workspaceRepository;
        this.roleRepository = roleRepository;
        this.shiftRepository = shiftRepository;
        this.tenantRepository = tenantRepository;
    }

    /** Resolved names for one page of audit rows. */
    public record Names(Map<UUID, String> actorNames, Map<String, String> entityNames) {
        public String entityName(String entityType, String entityId) {
            return entityId == null ? null : entityNames.get(entityType + "::" + entityId);
        }
    }

    public Names resolve(List<AuditLog> rows) {
        Map<UUID, String> actorNames = resolveActors(rows);
        Map<String, String> entityNames = resolveEntities(rows);
        return new Names(actorNames, entityNames);
    }

    private Map<UUID, String> resolveActors(List<AuditLog> rows) {
        Set<UUID> ids = rows.stream()
                .map(AuditLog::getActorId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        Map<UUID, String> out = new HashMap<>();
        try {
            userRepository.findAllById(ids).forEach(u -> {
                String name = StringUtils.hasText(u.getDisplayName()) ? u.getDisplayName() : u.getEmail();
                if (StringUtils.hasText(name)) out.put(u.getId(), name);
            });
        } catch (Exception e) {
            log.warn("Audit actor name resolution failed: {}", e.getMessage());
        }
        return out;
    }

    private Map<String, String> resolveEntities(List<AuditLog> rows) {
        // group the UUID entityIds we can resolve, by (normalised) entity type
        Map<String, Set<UUID>> idsByType = new HashMap<>();
        for (AuditLog row : rows) {
            String type = row.getEntityType();
            UUID id = tryUuid(row.getEntityId());
            if (type == null || id == null) continue;
            idsByType.computeIfAbsent(type.toLowerCase(), k -> new HashSet<>()).add(id);
        }

        Map<String, String> out = new HashMap<>();
        lookup(out, "user", idsByType, ids -> collect(userRepository.findAllById(ids),
                u -> u.getId(), u -> StringUtils.hasText(u.getDisplayName()) ? u.getDisplayName() : u.getEmail()));
        lookup(out, "employee", idsByType, ids -> collect(employeeRepository.findAllById(ids),
                e -> e.getId(), e -> joinName(e.getLastName(), e.getFirstName())));
        lookup(out, "site", idsByType, ids -> collect(siteRepository.findAllById(ids), s -> s.getId(), s -> s.getName()));
        lookup(out, "workspace", idsByType, ids -> collect(workspaceRepository.findAllById(ids), w -> w.getId(), w -> w.getName()));
        lookup(out, "role", idsByType, ids -> collect(roleRepository.findAllById(ids), r -> r.getId(), r -> r.getName()));
        lookup(out, "shift", idsByType, ids -> collect(shiftRepository.findAllById(ids), sh -> sh.getId(), sh -> sh.getName()));
        lookup(out, "tenant", idsByType, ids ->
                collect(tenantRepository.findAllByIdInAndDeletedAtIsNull(ids), t -> t.getId(), t -> t.getName()));

        // Second pass for link-type entities (UserRole, Assignment, WorkspaceMember, …) whose
        // own id means nothing to a reader — the useful name is the person the row is ABOUT,
        // carried as userId / employeeId inside the snapshot.
        Set<UUID> snapshotUserIds = new HashSet<>();
        Set<UUID> snapshotEmployeeIds = new HashSet<>();
        for (AuditLog row : rows) {
            if (row.getEntityType() == null || row.getEntityId() == null) continue;
            if (out.containsKey(row.getEntityType() + "::" + row.getEntityId())) continue;
            addSnapshotRef(snapshotUserIds, row, "userId");
            addSnapshotRef(snapshotEmployeeIds, row, "employeeId");
        }
        Map<UUID, String> snapUserNames = snapshotUserIds.isEmpty() ? Map.of()
                : safe(() -> collect(userRepository.findAllById(snapshotUserIds),
                    u -> u.getId(), u -> StringUtils.hasText(u.getDisplayName()) ? u.getDisplayName() : u.getEmail()),
                    Map.of());
        Map<UUID, String> snapEmpNames = snapshotEmployeeIds.isEmpty() ? Map.of()
                : safe(() -> collect(employeeRepository.findAllById(snapshotEmployeeIds),
                    e -> e.getId(), e -> joinName(e.getLastName(), e.getFirstName())), Map.of());

        // snapshot fallback for everything still unresolved (skips AccessControl-style rows)
        for (AuditLog row : rows) {
            String type = row.getEntityType();
            String id = row.getEntityId();
            if (type == null || id == null || "accesscontrol".equalsIgnoreCase(type)) continue;
            String key = type + "::" + id;
            if (out.containsKey(key)) continue;
            String name = fromSnapshot(row.getNewValue());
            if (name == null) name = fromSnapshot(row.getOldValue());
            if (name == null) name = refName(row, "userId", snapUserNames);
            if (name == null) name = refName(row, "employeeId", snapEmpNames);
            if (name != null) out.put(key, name);
        }
        return out;
    }

    private void addSnapshotRef(Set<UUID> target, AuditLog row, String key) {
        UUID id = snapshotUuid(row.getNewValue(), key);
        if (id == null) id = snapshotUuid(row.getOldValue(), key);
        if (id != null) target.add(id);
    }

    private String refName(AuditLog row, String key, Map<UUID, String> names) {
        UUID id = snapshotUuid(row.getNewValue(), key);
        if (id == null) id = snapshotUuid(row.getOldValue(), key);
        return id == null ? null : names.get(id);
    }

    private static UUID snapshotUuid(Map<String, Object> snapshot, String key) {
        if (snapshot == null) return null;
        Object v = snapshot.get(key);
        return v instanceof String s ? tryUuid(s) : null;
    }

    private <T> T safe(java.util.function.Supplier<T> supplier, T fallback) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("Audit snapshot-ref name resolution failed: {}", e.getMessage());
            return fallback;
        }
    }

    private void lookup(Map<String, String> out, String type, Map<String, Set<UUID>> idsByType,
                        Function<Set<UUID>, Map<UUID, String>> loader) {
        Set<UUID> ids = idsByType.get(type);
        if (ids == null || ids.isEmpty()) return;
        try {
            loader.apply(ids).forEach((id, name) -> {
                if (StringUtils.hasText(name)) {
                    // key uses the ORIGINAL entityType casing the rows carry ("Employee", "Site", …)
                    out.put(capitalize(type) + "::" + id, name);
                }
            });
        } catch (Exception e) {
            log.warn("Audit entity name resolution failed for type {}: {}", type, e.getMessage());
        }
    }

    private <T> Map<UUID, String> collect(Iterable<T> entities, Function<T, UUID> id, Function<T, String> name) {
        Map<UUID, String> m = new HashMap<>();
        entities.forEach(e -> m.put(id.apply(e), name.apply(e)));
        return m;
    }

    private static String joinName(String last, String first) {
        String l = last == null ? "" : last.trim();
        String f = first == null ? "" : first.trim();
        String joined = (l + " " + f).trim();
        return joined.isEmpty() ? null : joined;
    }

    private static UUID tryUuid(String value) {
        if (value == null || value.length() != 36) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final List<String> SNAPSHOT_NAME_KEYS =
            List.of("name", "displayName", "title", "employeeName", "siteName", "fullName");

    private static String fromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return null;
        for (String key : SNAPSHOT_NAME_KEYS) {
            Object v = snapshot.get(key);
            if (v instanceof String s && StringUtils.hasText(s)) return s;
        }
        // person name from a firstName/lastName snapshot (checked before email/slug so the
        // audit shows "Nguyễn Văn An", not a masked email)
        Object last = snapshot.get("lastName");
        Object first = snapshot.get("firstName");
        if (last instanceof String || first instanceof String) {
            String joined = joinName(last instanceof String s ? s : null, first instanceof String s ? s : null);
            if (joined != null) return joined;
        }
        for (String key : List.of("email", "slug", "employeeCode")) {
            Object v = snapshot.get(key);
            if (v instanceof String s && StringUtils.hasText(s)) return s;
        }
        return null;
    }

    private static String capitalize(String lower) {
        return switch (lower) {
            case "user" -> "USER";
            default -> Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        };
    }
}

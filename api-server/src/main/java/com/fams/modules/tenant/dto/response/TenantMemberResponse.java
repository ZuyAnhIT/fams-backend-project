package com.fams.modules.tenant.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "One person who holds at least one role in this tenant — the owner, any admin/HR/"
        + "supervisor, or a regular employee — regardless of whether they also have an HR Employee "
        + "profile. Broader than the Employee list, which only covers people onboarded through HR.")
public class TenantMemberResponse {

    @Schema(description = "User UUID")
    private UUID userId;

    @Schema(description = "Display name — from the linked Employee HR profile if one exists, else the "
            + "account's own display name")
    private String displayName;

    @Schema(description = "Email or phone, whichever the account has")
    private String contact;

    @Schema(description = "True if this person is the tenant's current owner")
    @JsonProperty("isOwner")
    private boolean isOwner;

    @Schema(description = "Every role name this person currently holds in this tenant")
    private List<String> roleNames;

    @Schema(description = "Matching user-role assignment UUIDs, same order as roleNames — pass one to "
            + "DELETE /user-roles/{id} to revoke just that role")
    private List<UUID> userRoleIds;

    @Schema(description = "True if this person also has an HR Employee profile (department/position/"
            + "employee code) — false for e.g. an owner/admin who was never onboarded through HR")
    private boolean hasEmployeeProfile;

    @Schema(description = "HR employee UUID, only present if hasEmployeeProfile is true — link to the "
            + "full Employee profile screen")
    private UUID employeeId;

    @Schema(description = "Job position, only present if hasEmployeeProfile is true")
    private String position;

    @Schema(description = "Department, only present if hasEmployeeProfile is true")
    private String department;

    @Schema(description = "Earliest role assignment date for this person in this tenant")
    private OffsetDateTime memberSince;
}

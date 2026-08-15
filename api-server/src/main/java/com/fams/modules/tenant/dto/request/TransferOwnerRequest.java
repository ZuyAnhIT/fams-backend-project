package com.fams.modules.tenant.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import lombok.Data;

import java.util.UUID;

@Data
@Schema(description = "Transfer this tenant's ownership to another existing member of the same tenant. "
        + "Exactly one of newOwnerUserId/newOwnerEmail is required.")
public class TransferOwnerRequest {

    @Schema(description = "UUID of the new owner — must already hold an active role in this tenant",
            example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID newOwnerUserId;

    @Schema(description = "Alternative to newOwnerUserId — look up the new owner by email instead",
            example = "new-owner@example.com")
    @Email(message = "newOwnerEmail must be a valid email address")
    private String newOwnerEmail;
}

package com.fams.modules.employee.controller;

import com.fams.modules.employee.dto.response.FaceIdStatusDto;
import com.fams.modules.employee.service.FaceIdService;
import com.fams.shared.response.ApiResponse;
import com.fams.shared.security.FamsUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Slf4j
@Tag(name = "Face ID", description = "Face ID enrollment review queue (HR/Admin)")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/face-id")
public class FaceIdReviewController {

    private final FaceIdService faceIdService;

    public FaceIdReviewController(FaceIdService faceIdService) {
        this.faceIdService = faceIdService;
    }

    @Operation(summary = "List Face ID enrollments pending HR review",
        description = "Every employee with a submitted-but-undecided enrollment/re-enrollment batch, "
                    + "restricted to the caller's allowed sites (site-scoped roles see only their sites' employees). "
                    + "Requires face_id:manage permission.")
    @GetMapping("/pending-review")
    public ResponseEntity<ApiResponse<List<FaceIdStatusDto>>> listPendingReview(
            @Parameter(description = "Tenant UUID") @PathVariable UUID tenantId,
            @AuthenticationPrincipal FamsUserDetails userDetails) {
        log.info("Face ID pending review list tenantId={} by={}", tenantId, userDetails.getUserId());
        List<FaceIdStatusDto> response = faceIdService.listPendingReview(
                tenantId, userDetails.getUserId(), userDetails.isPlatformAdmin());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}

package com.fams.modules.employee.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@Schema(description = "A started active-liveness challenge — client must perform each action "
        + "in order and submit one frame per action to POST .../liveness-challenge/{challengeId}/frames")
public class LivenessChallengeResponse {

    @Schema(description = "Challenge UUID")
    private UUID challengeId;

    @Schema(description = "Ordered actions the client must perform, one frame each, in this exact order",
            example = "[\"center\", \"turn_left\", \"blink\"]",
            allowableValues = {"center", "turn_left", "turn_right", "look_up", "look_down", "blink"})
    private List<String> actions;

    @Schema(description = "Challenge expires after this instant — submit frames before then")
    private OffsetDateTime expiresAt;
}

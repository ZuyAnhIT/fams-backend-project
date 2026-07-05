package com.fams.modules.checkin.controller;

import com.fams.modules.checkin.dto.request.FaceResultCallbackRequest;
import com.fams.modules.checkin.repository.CheckinRepository;
import com.fams.modules.randomcheck.service.CheckResponseService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Hidden
@Slf4j
@RestController
@RequestMapping("/internal/ai-callback")
public class FaceResultCallbackController {

    private static final String HEADER_SECRET = "X-Internal-Secret";

    private final String internalSecret;
    private final CheckinRepository checkinRepository;
    private final CheckResponseService checkResponseService;

    public FaceResultCallbackController(
            @Value("${app.ai.internal-secret}") String internalSecret,
            CheckinRepository checkinRepository,
            CheckResponseService checkResponseService) {
        this.internalSecret = internalSecret;
        this.checkinRepository = checkinRepository;
        this.checkResponseService = checkResponseService;
    }

    @PostMapping("/face-result")
    @Transactional
    public ResponseEntity<Void> handleFaceResult(
            @RequestHeader(value = HEADER_SECRET, required = false) String secret,
            @RequestBody FaceResultCallbackRequest request) {
        if (!internalSecret.equals(secret)) {
            log.warn("Face result callback rejected: invalid secret sourceId={}", request.getSourceId());
            return ResponseEntity.status(403).build();
        }

        if ("checkin".equals(request.getSourceType())) {
            checkinRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                            request.getSourceId(), request.getTenantId())
                    .ifPresent(record -> {
                        record.setFaceVerified(request.getFaceVerified());
                        record.setLivenessVerified(request.getLivenessVerified());
                        record.setFaceVerifyScore(request.getFaceVerifyScore());
                        checkinRepository.save(record);
                        log.info("Face result recorded: checkinId={} faceVerified={} score={} error={}",
                                record.getId(), request.getFaceVerified(),
                                request.getFaceVerifyScore(), request.getErrorCode());
                    });
        } else if ("check_response".equals(request.getSourceType())) {
            checkResponseService.applyFaceResult(
                    request.getSourceId(),
                    request.getFaceVerified(),
                    request.getLivenessVerified(),
                    request.getFaceVerifyScore());
        } else {
            log.warn("Unhandled callback sourceType={} sourceId={}",
                    request.getSourceType(), request.getSourceId());
        }

        return ResponseEntity.ok().build();
    }
}

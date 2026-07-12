package com.fams.modules.checkin.dto.response;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class SyncResultItem {

    UUID clientNonce;
    /** accepted | rejected | conflict */
    String status;
    String reason;
    UUID checkinRecordId;
}

package com.fams.shared.time;

import java.time.ZoneId;

/** Canonical business timezone while FAMS is operated exclusively in Vietnam. */
public final class VietnamTime {

    public static final String ID = "Asia/Ho_Chi_Minh";
    public static final ZoneId ZONE = ZoneId.of(ID);

    private VietnamTime() {
    }
}

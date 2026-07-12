package com.fams.shared.monitoring;

import com.google.firebase.FirebaseApp;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

@Component("fcm")
public class FcmHealthIndicator extends AbstractHealthIndicator {

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                builder.down().withDetail("error", "FirebaseApp not initialised — FCM credentials not loaded");
            } else {
                builder.up().withDetail("app_name", FirebaseApp.getInstance().getName());
            }
        } catch (Exception e) {
            builder.down().withDetail("error", e.getMessage());
        }
    }
}

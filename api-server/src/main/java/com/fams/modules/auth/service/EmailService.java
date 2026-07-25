package com.fams.modules.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Every send method here is {@code @Async}: the SMTP round-trip (connect, STARTTLS,
 * auth, and — when the send is rejected, e.g. a quota error — the server's response)
 * routinely takes several seconds, and callers (register, forgot-password, invite)
 * must never block their HTTP response on that. The caller's own DB/Redis writes
 * always happen first and synchronously, so firing the email off in the background
 * loses nothing but the email itself if it fails — never the primary action.
 */
@Slf4j
@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String from;

    public EmailService(JavaMailSender mailSender,
                        @Value("${spring.mail.username}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Async
    public void sendPasswordResetEmail(String to, String resetUrl) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject("FAMS - Password Reset Request");
        msg.setText(
                "You requested a password reset for your FAMS account.\n\n" +
                "Click the link below to set a new password:\n\n" +
                resetUrl + "\n\n" +
                "This link will expire in 1 hour.\n\n" +
                "If you did not request this, please ignore this email — your password will not change."
        );
        sendSafely(msg, "password reset", to);
    }

    /**
     * Security alert sent the moment an account gets locked from repeated failed logins
     * (see AuthService.login / AppConstants.MAX_FAILED_ATTEMPTS). Gives the real owner two
     * ways back in: wait out {@code lockedUntil}, or prove identity via password reset right
     * now — PasswordResetService.resetPassword clears the lock as soon as that succeeds, so
     * this is a genuine alternative, not just a suggestion.
     */
    @Async
    public void sendAccountLockedEmail(String to, String lockedUntilFormatted, String forgotPasswordUrl) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject("FAMS - Your account has been temporarily locked");
        msg.setText(
                "We locked your FAMS account after several failed login attempts.\n\n" +
                "It will unlock automatically at: " + lockedUntilFormatted + "\n\n" +
                "If this was you and you'd like back in sooner, reset your password now:\n\n" +
                forgotPasswordUrl + "\n\n" +
                "If you did not attempt to log in, someone else may be trying to access your account — " +
                "resetting your password now is recommended."
        );
        sendSafely(msg, "account locked", to);
    }

    @Async
    public void sendInvitationEmail(String to, String acceptUrl, int expiryDays) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject("FAMS - You have been invited to join");
        msg.setText(
                "You have been invited to join a company on FAMS (Field Attendance Management System).\n\n" +
                "Click the link below to accept your invitation and set up your account:\n\n" +
                acceptUrl + "\n\n" +
                "This invitation will expire in " + expiryDays + " days.\n\n" +
                "If you did not expect this invitation, please ignore this email."
        );
        sendSafely(msg, "invitation", to);
    }

    @Async
    public void sendPlatformInvitationEmail(String to, String acceptUrl, int expiryDays) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject("FAMS - You have been invited to join the platform team");
        msg.setText(
                "You have been invited to join the FAMS (Field Attendance Management System) platform team.\n\n" +
                "Click the link below to accept your invitation and set up your account:\n\n" +
                acceptUrl + "\n\n" +
                "This invitation will expire in " + expiryDays + " days.\n\n" +
                "If you did not expect this invitation, please ignore this email."
        );
        sendSafely(msg, "platform invitation", to);
    }

    /**
     * Deliberately NOT async and NOT wrapped by {@link #sendSafely}: the only caller,
     * {@code UserDeviceService.sendEmailFallback}, already has its own try/catch that
     * records the real outcome ("FALLBACK_EMAIL_SENT" vs "FALLBACK_EMAIL_FAILED") in
     * {@code notification_delivery_logs} — swallowing the exception here or making it
     * async would make that tracking always report success regardless of what
     * actually happened.
     */
    public void sendNotificationFallback(String to, String title, String body) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject("FAMS - " + title);
        msg.setText(body + "\n\n(This message was delivered by email because push notification could not be sent.)");
        mailSender.send(msg);
        log.info("Sent notification fallback email to {}", to);
    }

    @Async
    public void sendVerificationEmail(String to, String verificationUrl) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject("FAMS - Verify your email address");
        msg.setText(
                "Welcome to FAMS!\n\n" +
                "Please click the link below to verify your email address:\n\n" +
                verificationUrl + "\n\n" +
                "This link will expire in 24 hours.\n\n" +
                "If you did not register, please ignore this email."
        );
        sendSafely(msg, "verification", to);
    }

    /**
     * Sends and swallows any SMTP-layer failure (quota exceeded, provider outage,
     * bad credentials...) so a flaky mail server never aborts the caller's primary
     * action (account creation, password reset, invitation) — only the email itself
     * is lost, not the whole business transaction. Callers already have their own
     * fallback for the user (resend-verification, request-reset-again, resend-invite),
     * so a missing email is recoverable; a rolled-back account/token is not.
     */
    private void sendSafely(SimpleMailMessage msg, String kind, String to) {
        try {
            mailSender.send(msg);
            log.info("Sent {} email to {}", kind, to);
        } catch (MailException e) {
            log.error("Failed to send {} email to {}: {}", kind, to, e.getMessage(), e);
        }
    }
}

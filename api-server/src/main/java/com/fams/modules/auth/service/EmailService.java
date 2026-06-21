package com.fams.modules.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

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
        mailSender.send(msg);
        log.info("Password reset email sent to {}", to);
    }

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
        mailSender.send(msg);
        log.info("Invitation email sent to {}", to);
    }

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
        mailSender.send(msg);
        log.info("Verification email sent to {}", to);
    }
}

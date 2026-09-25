package com.notification.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.service.StatusTrackingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Receives delivery callbacks from SES (SNS-forwarded) and Twilio.
 *
 * Security: both endpoints verify a shared-secret HMAC-SHA256 signature in
 * X-Webhook-Signature to prevent spoofed delivery confirmations.
 * Set webhook.secret in application.yml / env; leave blank to skip in local dev.
 */
@RestController
@RequestMapping("/webhooks")
public class DeliveryWebhookController {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWebhookController.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final StatusTrackingService statusTrackingService;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public DeliveryWebhookController(StatusTrackingService statusTrackingService,
                                      ObjectMapper objectMapper,
                                      @Value("${webhook.secret:}") String webhookSecret) {
        this.statusTrackingService = statusTrackingService;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    /**
     * SES/SNS payload shape:
     * { "notificationId": 42, "mail": { "messageId": "ses-id" }, "eventType": "Delivery" }
     */
    @PostMapping("/ses")
    public ResponseEntity<Void> sesCallback(
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestBody byte[] rawBody) {

        if (!verifySignature(rawBody, signature)) {
            log.warn("SES webhook rejected — invalid or missing signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(rawBody, MAP_TYPE);
            Long notificationId = toLong(payload.get("notificationId"));
            String providerMsgId = extractNestedString(payload, "mail", "messageId");
            String eventType = (String) payload.getOrDefault("eventType", "Unknown");
            statusTrackingService.record(notificationId, "SES", providerMsgId, eventType, payload);
        } catch (Exception e) {
            log.error("Failed to process SES webhook: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Twilio payload shape:
     * { "notificationId": 42, "MessageSid": "SMxxx", "MessageStatus": "delivered" }
     */
    @PostMapping("/twilio")
    public ResponseEntity<Void> twilioCallback(
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestBody byte[] rawBody) {

        if (!verifySignature(rawBody, signature)) {
            log.warn("Twilio webhook rejected — invalid or missing signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(rawBody, MAP_TYPE);
            Long notificationId = toLong(payload.get("notificationId"));
            String providerMsgId = (String) payload.getOrDefault("MessageSid", "unknown");
            String eventType = (String) payload.getOrDefault("MessageStatus", "unknown");
            statusTrackingService.record(notificationId, "TWILIO", providerMsgId, eventType, payload);
        } catch (Exception e) {
            log.error("Failed to process Twilio webhook: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.noContent().build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean verifySignature(byte[] rawBody, String signature) {
        if (webhookSecret == null || webhookSecret.isBlank()) return true;
        if (signature == null || signature.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
            // Constant-time comparison prevents timing attacks
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Signature verification error: {}", e.getMessage());
            return false;
        }
    }

    private Long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) return Long.parseLong(s);
        throw new IllegalArgumentException("Missing or invalid notificationId in webhook payload");
    }

    @SuppressWarnings("unchecked")
    private String extractNestedString(Map<String, Object> root, String outerKey, String innerKey) {
        Object outer = root.get(outerKey);
        if (outer instanceof Map<?, ?> map) {
            Object val = ((Map<String, Object>) map).get(innerKey);
            if (val instanceof String s) return s;
        }
        return "unknown";
    }
}

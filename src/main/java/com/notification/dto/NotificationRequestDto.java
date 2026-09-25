package com.notification.dto;

import com.notification.domain.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record NotificationRequestDto(
        @NotBlank String tenantId,
        @NotNull Channel channel,
        @NotBlank String recipient,
        @NotBlank String templateId,
        Map<String, String> data,
        String idempotencyKey   // optional; server generates one if absent
) {}

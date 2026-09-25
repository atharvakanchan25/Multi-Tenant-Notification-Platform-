package com.notification.dto;

import com.notification.domain.Channel;

import java.util.Map;

public record NotificationEvent(
        Long notificationId,
        String idempotencyKey,
        String tenantId,
        Channel channel,
        String recipient,
        String templateId,
        Map<String, String> data
) {}

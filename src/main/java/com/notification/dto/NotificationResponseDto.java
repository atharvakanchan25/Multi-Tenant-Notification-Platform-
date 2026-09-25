package com.notification.dto;

import com.notification.domain.NotificationStatus;

public record NotificationResponseDto(
        Long notificationId,
        NotificationStatus status,
        String message
) {}

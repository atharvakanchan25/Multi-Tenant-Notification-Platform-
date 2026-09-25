package com.notification.repository;

import com.notification.domain.NotificationRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationRequestRepository extends JpaRepository<NotificationRequest, Long> {
    Optional<NotificationRequest> findByIdempotencyKey(String idempotencyKey);
}

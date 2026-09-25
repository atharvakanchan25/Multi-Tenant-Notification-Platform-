package com.notification.repository;

import com.notification.domain.DeliveryEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeliveryEventRepository extends JpaRepository<DeliveryEvent, Long> {
    List<DeliveryEvent> findByNotificationId(Long notificationId);
}

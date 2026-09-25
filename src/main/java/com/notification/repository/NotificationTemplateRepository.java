package com.notification.repository;

import com.notification.domain.Channel;
import com.notification.domain.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {
    Optional<NotificationTemplate> findByTenantIdAndTemplateIdAndChannel(
            String tenantId, String templateId, Channel channel);
}

package com.notification.repository;

import com.notification.domain.Channel;
import com.notification.domain.OptOut;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OptOutRepository extends JpaRepository<OptOut, Long> {
    boolean existsByTenantIdAndRecipientAndChannel(String tenantId, String recipient, Channel channel);
}

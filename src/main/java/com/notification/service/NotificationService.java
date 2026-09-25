package com.notification.service;

import com.notification.domain.NotificationRequest;
import com.notification.domain.NotificationStatus;
import com.notification.domain.Tenant;
import com.notification.dto.NotificationEvent;
import com.notification.dto.NotificationRequestDto;
import com.notification.repository.NotificationRequestRepository;
import com.notification.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationRequestRepository notificationRepo;
    private final TenantRepository tenantRepo;
    private final NotificationEventPublisher publisher;

    public NotificationService(NotificationRequestRepository notificationRepo,
                                TenantRepository tenantRepo,
                                NotificationEventPublisher publisher) {
        this.notificationRepo = notificationRepo;
        this.tenantRepo = tenantRepo;
        this.publisher = publisher;
    }

    @Transactional
    public NotificationRequest accept(NotificationRequestDto dto) {
        Tenant tenant = tenantRepo.findByTenantId(dto.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown tenant: " + dto.tenantId()));

        String idempotencyKey = (dto.idempotencyKey() != null && !dto.idempotencyKey().isBlank())
                ? dto.idempotencyKey()
                : UUID.randomUUID().toString();

        var existing = notificationRepo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) return existing.get();

        NotificationRequest record = new NotificationRequest();
        record.setTenantId(dto.tenantId());
        record.setChannel(dto.channel());
        record.setRecipient(dto.recipient());
        record.setTemplateId(dto.templateId());
        record.setData(dto.data());
        record.setIdempotencyKey(idempotencyKey);
        record.setStatus(NotificationStatus.PENDING);
        notificationRepo.save(record);

        // Publish to tier-partitioned topic so PAID tenants get dedicated consumer threads
        publisher.publish(new NotificationEvent(
                record.getId(), idempotencyKey,
                dto.tenantId(), dto.channel(),
                dto.recipient(), dto.templateId(), dto.data()
        ), tenant.getTier());

        return record;
    }
}

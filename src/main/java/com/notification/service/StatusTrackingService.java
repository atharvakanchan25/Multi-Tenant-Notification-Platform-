package com.notification.service;

import com.notification.domain.DeliveryEvent;
import com.notification.domain.NotificationStatus;
import com.notification.repository.DeliveryEventRepository;
import com.notification.repository.NotificationRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class StatusTrackingService {

    private static final Logger log = LoggerFactory.getLogger(StatusTrackingService.class);

    // Maps provider event types → internal NotificationStatus
    private static final Map<String, NotificationStatus> EVENT_STATUS_MAP = Map.of(
            "Delivery",  NotificationStatus.DELIVERED,
            "delivered", NotificationStatus.DELIVERED,
            "Bounce",    NotificationStatus.FAILED,
            "failed",    NotificationStatus.FAILED,
            "undelivered", NotificationStatus.FAILED,
            "Complaint", NotificationStatus.FAILED
    );

    private final DeliveryEventRepository deliveryEventRepo;
    private final NotificationRequestRepository notificationRepo;

    public StatusTrackingService(DeliveryEventRepository deliveryEventRepo,
                                  NotificationRequestRepository notificationRepo) {
        this.deliveryEventRepo = deliveryEventRepo;
        this.notificationRepo = notificationRepo;
    }

    /**
     * Persists a raw webhook payload as a DeliveryEvent and updates the
     * parent NotificationRequest status when the event type is terminal.
     *
     * @param notificationId  internal notification ID (resolved by webhook controller)
     * @param provider        "SES" | "TWILIO" | "PUSH"
     * @param providerMsgId   message ID assigned by the provider
     * @param eventType       provider-specific event string (e.g. "Delivery", "delivered")
     * @param rawPayload      full webhook body for audit
     */
    @Transactional
    public DeliveryEvent record(Long notificationId, String provider,
                                String providerMsgId, String eventType,
                                Map<String, Object> rawPayload) {
        DeliveryEvent event = new DeliveryEvent();
        event.setNotificationId(notificationId);
        event.setProvider(provider);
        event.setProviderMessageId(providerMsgId);
        event.setEventType(eventType);
        event.setRawPayload(rawPayload);
        deliveryEventRepo.save(event);

        NotificationStatus newStatus = EVENT_STATUS_MAP.get(eventType);
        if (newStatus != null) {
            notificationRepo.findById(notificationId).ifPresent(req -> {
                req.setStatus(newStatus);
                req.setUpdatedAt(java.time.Instant.now());
                notificationRepo.save(req);
                log.info("Status updated notificationId={} → {} via {} event={}",
                        notificationId, newStatus, provider, eventType);
            });
        } else {
            log.debug("Unrecognised event type '{}' from {} — stored but status unchanged", eventType, provider);
        }

        return event;
    }
}

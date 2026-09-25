package com.notification.adapter;

import com.notification.domain.Channel;
import com.notification.domain.NotificationRequest;
import com.notification.domain.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Secondary SMS provider — used when the primary (Twilio) circuit breaker is open.
 * In production this would integrate with e.g. AWS SNS or Vonage.
 * Marked as @Secondary so DispatchService can distinguish primary vs fallback.
 */
@Component
public class SecondarySmsFallbackAdapter implements ProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(SecondarySmsFallbackAdapter.class);
    public static final String PROVIDER_NAME = "SMS_FALLBACK";

    @Override
    public Channel channel() { return Channel.SMS; }

    @Override
    public String send(Tenant tenant, NotificationRequest request,
                       String renderedBody, String renderedSubject) {
        String msgId = "fallback-sms-" + UUID.randomUUID();
        log.info("[SMS FALLBACK] tenant={} recipient={} msgId={}",
                tenant.getTenantId(), request.getRecipient(), msgId);
        return msgId;
    }
}

package com.notification.adapter;

import com.notification.domain.Channel;
import com.notification.domain.NotificationRequest;
import com.notification.domain.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PushStubAdapter implements ProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(PushStubAdapter.class);

    @Override
    public Channel channel() { return Channel.PUSH; }

    @Override
    public String send(Tenant tenant, NotificationRequest request,
                       String renderedBody, String renderedSubject) {
        String msgId = "push-stub-" + UUID.randomUUID();
        log.info("[PUSH STUB] tenant={} recipient={} subject='{}' body='{}' msgId={}",
                tenant.getTenantId(), request.getRecipient(), renderedSubject, renderedBody, msgId);
        return msgId;
    }
}

package com.notification.adapter;

import com.notification.domain.Channel;
import com.notification.domain.NotificationRequest;
import com.notification.domain.Tenant;

public interface ProviderAdapter {

    /** The channel this adapter handles. */
    Channel channel();

    /**
     * Dispatch the notification. Returns the provider-assigned message ID.
     * Throws on any delivery failure so the caller can apply retry/CB logic.
     */
    String send(Tenant tenant, NotificationRequest request, String renderedBody, String renderedSubject);
}

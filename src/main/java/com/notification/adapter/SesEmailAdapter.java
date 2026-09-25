package com.notification.adapter;

import com.notification.domain.Channel;
import com.notification.domain.NotificationRequest;
import com.notification.domain.Tenant;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;

@Component
public class SesEmailAdapter implements ProviderAdapter {

    private final SesClient sesClient;

    public SesEmailAdapter(SesClient sesClient) {
        this.sesClient = sesClient;
    }

    @Override
    public Channel channel() { return Channel.EMAIL; }

    @Override
    public String send(Tenant tenant, NotificationRequest request,
                       String renderedBody, String renderedSubject) {
        SendEmailResponse response = sesClient.sendEmail(SendEmailRequest.builder()
                .source(tenant.getSenderEmail())
                .destination(Destination.builder().toAddresses(request.getRecipient()).build())
                .message(Message.builder()
                        .subject(Content.builder().data(renderedSubject).build())
                        .body(Body.builder()
                                .text(Content.builder().data(renderedBody).build())
                                .build())
                        .build())
                .build());
        return response.messageId();
    }
}

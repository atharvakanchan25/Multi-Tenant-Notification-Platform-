package com.notification.adapter;

import com.notification.domain.Channel;
import com.notification.domain.NotificationRequest;
import com.notification.domain.Tenant;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TwilioSmsAdapter implements ProviderAdapter {

    private final String fromNumber;

    public TwilioSmsAdapter(@Value("${twilio.account-sid}") String accountSid,
                             @Value("${twilio.auth-token}") String authToken,
                             @Value("${twilio.from-number}") String fromNumber) {
        Twilio.init(accountSid, authToken);
        this.fromNumber = fromNumber;
    }

    @Override
    public Channel channel() { return Channel.SMS; }

    @Override
    public String send(Tenant tenant, NotificationRequest request,
                       String renderedBody, String renderedSubject) {
        Message message = Message.creator(
                new PhoneNumber(request.getRecipient()),
                new PhoneNumber(fromNumber),
                renderedBody
        ).create();
        return message.getSid();
    }
}

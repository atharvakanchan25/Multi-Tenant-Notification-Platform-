package com.notification.service;

import com.notification.domain.Channel;
import com.notification.repository.OptOutRepository;
import org.springframework.stereotype.Service;

@Service
public class PreferenceService {

    private final OptOutRepository optOutRepo;

    public PreferenceService(OptOutRepository optOutRepo) {
        this.optOutRepo = optOutRepo;
    }

    public boolean isSuppressed(String tenantId, String recipient, Channel channel) {
        return optOutRepo.existsByTenantIdAndRecipientAndChannel(tenantId, recipient, channel);
    }
}

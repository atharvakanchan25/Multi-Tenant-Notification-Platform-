package com.notification.service;

/**
 * @deprecated Removed. All email dispatch routes through DispatchService → SesEmailAdapter.
 * This class is retained only to avoid breaking any external references during migration.
 * It is NOT registered as a Spring bean and has no functionality.
 */
@Deprecated(since = "2.0", forRemoval = true)
public class EmailService {
    private EmailService() {}
}

package com.aiconnect.llmgateway.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, UUID> {
    List<NotificationChannel> findByOrganizationIdAndEnabledTrue(UUID organizationId);
    List<NotificationChannel> findByOrganizationId(UUID organizationId);
}

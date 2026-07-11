package com.aiconnect.llmgateway.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {
    List<NotificationDelivery> findByIncidentIdInOrderByCreatedAtAsc(Collection<UUID> incidentIds);
}

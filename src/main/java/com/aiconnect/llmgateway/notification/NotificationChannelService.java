package com.aiconnect.llmgateway.notification;

import com.aiconnect.llmgateway.identity.AuditService;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationChannelService {
    private final OrganizationRepository organizations;
    private final NotificationChannelRepository channels;
    private final SecretCipher cipher;
    private final AuditService audit;

    public NotificationChannelService(OrganizationRepository organizations, NotificationChannelRepository channels,
                                      SecretCipher cipher, AuditService audit) {
        this.organizations = organizations;
        this.channels = channels;
        this.cipher = cipher;
        this.audit = audit;
    }

    @Transactional
    public NotificationChannel create(UUID organizationId, NotificationChannelType type, String target, String secret) {
        if (!organizations.existsById(organizationId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "The organization does not exist.");
        }
        NotificationChannel channel = channels.save(new NotificationChannel(
                organizationId, type, cipher.encrypt(target), cipher.encrypt(secret)));
        audit.record(organizationId, CurrentActor.userIdOrNull(), "NOTIFICATION_CHANNEL_CREATED",
                "NOTIFICATION_CHANNEL", channel.getId(), Map.of("type", type.name()));
        return channel;
    }

    public List<NotificationChannel> list(UUID organizationId) {
        return channels.findByOrganizationId(organizationId);
    }

    @Transactional
    public NotificationChannel setEnabled(UUID organizationId, UUID channelId, boolean enabled) {
        NotificationChannel channel = channels.findById(channelId)
                .filter(candidate -> candidate.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_CHANNEL_NOT_FOUND",
                        "The notification channel does not exist in this organization."));
        channel.setEnabled(enabled);
        NotificationChannel saved = channels.save(channel);
        audit.record(organizationId, CurrentActor.userIdOrNull(), "NOTIFICATION_CHANNEL_STATE_CHANGED",
                "NOTIFICATION_CHANNEL", channelId, Map.of("enabled", enabled));
        return saved;
    }
}

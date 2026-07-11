package com.aiconnect.llmgateway.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/organizations/{organizationId}/notification-channels")
public class NotificationChannelController {
    private final NotificationChannelService service;

    public NotificationChannelController(NotificationChannelService service) { this.service = service; }

    @PostMapping
    public ChannelView create(@PathVariable UUID organizationId, @Valid @RequestBody CreateChannel request) {
        return ChannelView.from(service.create(organizationId, request.type(), request.target(), request.secret()));
    }

    @GetMapping
    public List<ChannelView> list(@PathVariable UUID organizationId) {
        return service.list(organizationId).stream().map(ChannelView::from).toList();
    }

    @PatchMapping("/{channelId}")
    public ChannelView update(@PathVariable UUID organizationId, @PathVariable UUID channelId,
                              @Valid @RequestBody UpdateChannel request) {
        return ChannelView.from(service.setEnabled(organizationId, channelId, request.enabled()));
    }

    public record CreateChannel(@NotNull NotificationChannelType type, @NotBlank String target, String secret) { }
    public record UpdateChannel(boolean enabled) { }
    public record ChannelView(UUID id, UUID organizationId, NotificationChannelType type, boolean enabled) {
        static ChannelView from(NotificationChannel channel) {
            return new ChannelView(channel.getId(), channel.getOrganizationId(), channel.getChannelType(), channel.isEnabled());
        }
    }
}

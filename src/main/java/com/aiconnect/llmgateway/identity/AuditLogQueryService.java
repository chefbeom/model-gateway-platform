package com.aiconnect.llmgateway.identity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AuditLogQueryService {
    private final AuditLogRepository logs;
    private final AppUserRepository users;

    public AuditLogQueryService(AuditLogRepository logs, AppUserRepository users) {
        this.logs = logs;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public PageResult search(UUID organizationId, String action, String resourceType, UUID actorUserId,
                             Instant from, Instant to, int page, int size) {
        Specification<AuditLog> specification = Specification.unrestricted();
        if (organizationId != null) {
            specification = specification.and((root, query, criteria) -> criteria.equal(root.get("organizationId"), organizationId));
        }
        if (hasText(action)) {
            String pattern = "%" + action.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, criteria) -> criteria.like(criteria.lower(root.get("action")), pattern));
        }
        if (hasText(resourceType)) {
            String pattern = "%" + resourceType.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, criteria) -> criteria.like(criteria.lower(root.get("resourceType")), pattern));
        }
        if (actorUserId != null) {
            specification = specification.and((root, query, criteria) -> criteria.equal(root.get("actorUserId"), actorUserId));
        }
        if (from != null) {
            specification = specification.and((root, query, criteria) -> criteria.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            specification = specification.and((root, query, criteria) -> criteria.lessThan(root.get("createdAt"), to));
        }

        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AuditLog> result = logs.findAll(specification, pageable);
        Set<UUID> actorIds = result.getContent().stream().map(AuditLog::getActorUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, AppUser> actors = users.findAllById(actorIds).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
        List<AuditView> items = result.getContent().stream().map(log -> view(log, actors)).toList();
        return new PageResult(items, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    private AuditView view(AuditLog log, Map<UUID, AppUser> actors) {
        AppUser actor = log.getActorUserId() == null ? null : actors.get(log.getActorUserId());
        String actorEmail = actor == null ? (log.getActorUserId() == null ? "SYSTEM / BREAK-GLASS" : "삭제된 사용자") : actor.getEmail();
        return new AuditView(log.getId(), log.getOrganizationId(), log.getActorUserId(), actorEmail,
                log.getAction(), log.getResourceType(), log.getResourceId(), log.getDetailJson(), log.getCreatedAt());
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }

    public record PageResult(List<AuditView> items, int page, int size, long totalElements, int totalPages) { }
    public record AuditView(UUID id, UUID organizationId, UUID actorUserId, String actorEmail, String action,
                            String resourceType, String resourceId, String detailJson, Instant createdAt) { }
}

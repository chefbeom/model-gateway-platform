package com.aiconnect.llmgateway.playground;

import com.aiconnect.llmgateway.domain.PlaygroundConversation;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.PlaygroundConversationRepository;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Stores only encrypted chat text and attachment names; attachment bytes are deliberately discarded. */
@Service
public class PlaygroundConversationService {
    private static final TypeReference<List<StoredTurn>> TURN_LIST = new TypeReference<>() { };

    private final PlaygroundConversationRepository conversations;
    private final SecretCipher cipher;
    private final ObjectMapper mapper;

    public PlaygroundConversationService(PlaygroundConversationRepository conversations,
                                         SecretCipher cipher, ObjectMapper mapper) {
        this.conversations = conversations;
        this.cipher = cipher;
        this.mapper = mapper;
    }

    @Transactional
    public UUID begin(UUID organizationId, UUID targetId, String targetName, String modelId,
                      UUID requestedConversationId, String requestId, ObjectNode rawBody,
                      boolean streaming) {
        UUID actorId = CurrentActor.userIdOrNull();
        PlaygroundConversation conversation;
        List<StoredTurn> turns;
        if (requestedConversationId == null) {
            turns = initialTurns(rawBody);
            conversation = new PlaygroundConversation(organizationId, actorId, targetId,
                    targetName, modelId, encrypt(turns), turns.size());
        } else {
            conversation = findOwned(requestedConversationId, organizationId, actorId);
            if (!conversation.getTargetId().equals(targetId)) {
                throw new ApiException(HttpStatus.CONFLICT, "PLAYGROUND_CONVERSATION_MODEL_MISMATCH",
                        "대화 기록은 처음 선택한 모델에서만 이어갈 수 있습니다. 새 대화를 시작하세요.");
            }
            turns = decrypt(conversation.getEncryptedTranscript());
            StoredTurn latestUser = latestUserTurn(rawBody);
            turns.add(latestUser);
        }
        turns.add(new StoredTurn(UUID.randomUUID().toString(), "assistant", "", List.of(), "IN_PROGRESS",
                requestId, null, null, null, null, streaming, false, null, Instant.now()));
        conversation.update(encrypt(turns), turns.size());
        conversations.save(conversation);
        return conversation.getId();
    }

    @Transactional
    public void complete(UUID conversationId, String requestId, boolean successful, int httpStatus,
                         long latencyMs, Integer inputTokens, Integer outputTokens, String content,
                         String error, boolean streaming, boolean responseStreaming) {
        if (conversationId == null) return;
        PlaygroundConversation conversation = conversations.findById(conversationId).orElse(null);
        if (conversation == null) return;
        List<StoredTurn> turns = decrypt(conversation.getEncryptedTranscript());
        for (int index = turns.size() - 1; index >= 0; index--) {
            StoredTurn turn = turns.get(index);
            if (!"assistant".equals(turn.role()) || !requestId.equals(turn.requestId())) continue;
            turns.set(index, new StoredTurn(turn.id(), turn.role(), content == null ? "" : content,
                    turn.files(), successful ? "SUCCEEDED" : "FAILED", turn.requestId(), httpStatus,
                    latencyMs, inputTokens, outputTokens, streaming, responseStreaming,
                    safeError(error), turn.createdAt()));
            conversation.update(encrypt(turns), turns.size());
            conversations.save(conversation);
            return;
        }
    }

    @Transactional(readOnly = true)
    public List<ConversationSummary> list(UUID organizationId, String query) {
        List<PlaygroundConversation> found = conversations
                .findByOrganizationIdAndActorUserIdOrderByUpdatedAtDesc(organizationId, CurrentActor.userIdOrNull());
        String needle = query == null ? "" : query.trim().substring(0, Math.min(256, query.trim().length())).toLowerCase(Locale.ROOT);
        List<ConversationSummary> result = new ArrayList<>();
        for (PlaygroundConversation item : found) {
            List<StoredTurn> turns = decrypt(item.getEncryptedTranscript());
            String title = turns.stream().filter(turn -> "user".equals(turn.role()))
                    .map(StoredTurn::content).findFirst().orElse("");
            String searchable = turns.stream().map(StoredTurn::content).reduce("", (left, right) -> left + "\n" + right);
            if (!needle.isEmpty() && !contains(needle, title) && !contains(needle, searchable)
                    && !contains(needle, item.getModelId()) && !contains(needle, item.getTargetName())) continue;
            String preview = needle.isEmpty() ? title : matchSnippet(searchable, needle, title);
            result.add(new ConversationSummary(item.getId(), item.getTargetId(), item.getTargetName(),
                    item.getModelId(), shortText(title, 120), shortText(preview, 220),
                    item.getMessageCount(), item.getCreatedAt(), item.getUpdatedAt()));
        }
        return List.copyOf(result);
    }

    @Transactional(readOnly = true)
    public ConversationDetail get(UUID organizationId, UUID conversationId) {
        PlaygroundConversation item = findOwned(conversationId, organizationId, CurrentActor.userIdOrNull());
        List<StoredTurn> turns = decrypt(item.getEncryptedTranscript());
        String title = turns.stream().filter(turn -> "user".equals(turn.role())
                ).map(StoredTurn::content).findFirst().orElse("");
        return new ConversationDetail(new ConversationSummary(item.getId(), item.getTargetId(),
                item.getTargetName(), item.getModelId(), shortText(title, 120),
                shortText(title, 220), item.getMessageCount(), item.getCreatedAt(), item.getUpdatedAt()), turns);
    }

    @Transactional
    public void delete(UUID organizationId, UUID conversationId) {
        conversations.delete(findOwned(conversationId, organizationId, CurrentActor.userIdOrNull()));
    }

    private PlaygroundConversation findOwned(UUID conversationId, UUID organizationId, UUID actorId) {
        return conversations.findByIdAndOrganizationIdAndActorUserId(conversationId, organizationId, actorId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAYGROUND_CONVERSATION_NOT_FOUND",
                        "대화 기록을 찾을 수 없거나 열람 권한이 없습니다."));
    }

    private List<StoredTurn> initialTurns(ObjectNode raw) {
        List<StoredTurn> turns = new ArrayList<>();
        // Use the original text-only chat messages, not the transformed upstream
        // messages which may contain inline base64 image/file payloads.
        JsonNode messages = raw.path("messages");
        int lastUserIndex = -1;
        for (int i = 0; i < messages.size(); i++) if ("user".equals(messages.get(i).path("role").asText())) lastUserIndex = i;
        for (int i = 0; i < messages.size(); i++) {
            JsonNode message = messages.get(i);
            String role = message.path("role").asText();
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            List<String> files = "user".equals(role) ? attachmentNames(raw, i, lastUserIndex) : List.of();
            turns.add(new StoredTurn(UUID.randomUUID().toString(), role, message.path("content").asText(),
                    files, "SUCCEEDED", null, null, null, null, null, null, null, null, Instant.now()));
        }
        return turns;
    }

    private StoredTurn latestUserTurn(ObjectNode raw) {
        JsonNode messages = raw.path("messages");
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode message = messages.get(i);
            if ("user".equals(message.path("role").asText())) {
                return new StoredTurn(UUID.randomUUID().toString(), "user", message.path("content").asText(),
                        attachmentNames(raw, i, i), "SUCCEEDED", null, null, null, null,
                        null, null, null, null, Instant.now());
            }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "PLAYGROUND_USER_MESSAGE_REQUIRED", "사용자 메시지가 필요합니다.");
    }

    private List<String> attachmentNames(ObjectNode raw, int messageIndex, int lastUserIndex) {
        JsonNode attachments = raw.path("attachments");
        if (!attachments.isArray()) return List.of();
        List<String> names = new ArrayList<>();
        for (JsonNode attachment : attachments) {
            int attachedIndex = attachment.has("messageIndex") ? attachment.path("messageIndex").asInt(-1) : lastUserIndex;
            if (attachedIndex == messageIndex) {
                String name = attachment.path("name").asText("attachment");
                if (names.size() < 20 && !names.contains(name)) names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private String encrypt(List<StoredTurn> turns) {
        try { return cipher.encrypt(mapper.writeValueAsString(turns)); }
        catch (Exception exception) { throw new IllegalStateException("Could not store playground conversation", exception); }
    }

    private List<StoredTurn> decrypt(String encrypted) {
        try { return mapper.readValue(cipher.decrypt(encrypted), TURN_LIST); }
        catch (Exception exception) { throw new IllegalStateException("Could not read encrypted playground history; verify the gateway encryption key", exception); }
    }

    private String safeError(String error) {
        if (error == null || error.isBlank()) return null;
        return error.substring(0, Math.min(8_000, error.length()));
    }

    private boolean contains(String needle, String haystack) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private String matchSnippet(String text, String needle, String fallback) {
        String flattened = text.replaceAll("\\s+", " ").trim();
        int index = flattened.toLowerCase(Locale.ROOT).indexOf(needle);
        if (index < 0) return fallback;
        int start = Math.max(0, index - 70);
        int end = Math.min(flattened.length(), index + needle.length() + 110);
        return (start > 0 ? "…" : "") + flattened.substring(start, end) + (end < flattened.length() ? "…" : "");
    }

    private String shortText(String text, int max) {
        String flattened = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= max ? flattened : flattened.substring(0, max - 1) + "…";
    }

    public record StoredTurn(String id, String role, String content, List<String> files, String status,
                             String requestId, Integer httpStatus, Long latencyMs, Integer inputTokens,
                             Integer outputTokens, Boolean stream, Boolean responseStream, String error,
                             Instant createdAt) { }

    public record ConversationSummary(UUID conversationId, UUID targetId, String targetName, String modelId,
                                      String title, String preview, int messageCount,
                                      Instant createdAt, Instant updatedAt) { }

    public record ConversationDetail(ConversationSummary conversation, List<StoredTurn> turns) { }
}

package com.aiconnect.llmgateway.playground;

import com.aiconnect.llmgateway.domain.PlaygroundConversation;
import com.aiconnect.llmgateway.config.GatewayProperties;
import com.aiconnect.llmgateway.repository.PlaygroundConversationRepository;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaygroundConversationServiceTest {
    private final PlaygroundConversationRepository repository = mock(PlaygroundConversationRepository.class);
    private final SecretCipher cipher = testCipher();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final PlaygroundConversationService service = new PlaygroundConversationService(repository, cipher, mapper);

    @Test
    void storesTranscriptEncryptedAndNeverPersistsAttachmentBytes() throws Exception {
        AtomicReference<PlaygroundConversation> stored = new AtomicReference<>();
        when(repository.save(any(PlaygroundConversation.class))).thenAnswer(call -> {
            PlaygroundConversation conversation = call.getArgument(0);
            stored.set(conversation);
            return conversation;
        });
        UUID organizationId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ObjectNode request = mapper.createObjectNode();
        request.putArray("messages").addObject().put("role", "user").put("content", "What is in this image?");
        request.putArray("attachments").addObject().put("name", "private.png")
                .put("mediaType", "image/png").put("base64", "VERY_SECRET_IMAGE_BYTES").put("messageIndex", 0);
        UUID conversationId = service.begin(organizationId, targetId, "Vision model", "vision-v1",
                null, "request-1", request, false);
        when(repository.findById(conversationId)).thenAnswer(call -> Optional.ofNullable(stored.get()));
        PlaygroundConversation saved = stored.get();
        assertFalse(saved.getEncryptedTranscript().contains("What is in this image?"));
        String transcript = cipher.decrypt(saved.getEncryptedTranscript());
        assertTrue(transcript.contains("What is in this image?"));
        assertTrue(transcript.contains("private.png"));
        assertFalse(transcript.contains("VERY_SECRET_IMAGE_BYTES"));
        assertFalse(saved.getEncryptedTranscript().contains("VERY_SECRET_IMAGE_BYTES"));

        service.complete(conversationId, "request-1", true, 200, 80, 9, 4,
                "A landscape.", null, false, false);
        when(repository.findByIdAndOrganizationIdAndActorUserId(eq(conversationId), eq(organizationId), isNull()))
                .thenReturn(Optional.of(saved));
        var detail = service.get(organizationId, conversationId);
        assertEquals(2, detail.turns().size());
        assertEquals("A landscape.", detail.turns().get(1).content());
        assertEquals("private.png", detail.turns().get(0).files().get(0));
    }

    @Test
    void conversationCannotBeReadOutsideItsOwnerScope() {
        UUID organizationId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(repository.findByIdAndOrganizationIdAndActorUserId(eq(conversationId), eq(organizationId), isNull()))
                .thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.get(organizationId, conversationId));
    }

    private static SecretCipher testCipher() {
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.encryptionKey()).thenReturn("playground-unit-test-only-encryption-key");
        return new SecretCipher(properties);
    }
}

package com.aiconnect.llmgateway.service;

import com.aiconnect.llmgateway.config.GatewayProperties;
import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApiKeyServiceTest {
    @Test
    void issuesOnlyTheRawKeyOnceAndStoresOnlyItsHmac() {
        ApiKeyRepository keys = mock(ApiKeyRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        UUID projectId = UUID.randomUUID();
        when(projects.findById(projectId)).thenReturn(Optional.of(new Project(UUID.randomUUID(), "consumer")));
        when(keys.save(any(ApiKey.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ApiKeyService service = new ApiKeyService(keys, projects, new GatewayProperties("admin", "test-pepper", "key", 30_000, 1_000, 5_000));

        IssuedApiKey issued = service.issue(projectId, "integration", null);

        ArgumentCaptor<ApiKey> saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(keys).save(saved.capture());
        assertThat(issued.secret()).startsWith("sk_llmg_");
        assertThat(saved.getValue().getSecretHash()).doesNotContain(issued.secret());
        assertThat(saved.getValue().getKeyPrefix()).isEqualTo(issued.secret().substring(0, issued.secret().indexOf('.')));
    }

    @Test
    void authenticatesOnlyTheMatchingStoredKey() {
        ApiKeyRepository keys = mock(ApiKeyRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        UUID projectId = UUID.randomUUID();
        when(projects.findById(projectId)).thenReturn(Optional.of(new Project(UUID.randomUUID(), "consumer")));
        when(keys.save(any(ApiKey.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ApiKeyService service = new ApiKeyService(keys, projects, new GatewayProperties("admin", "test-pepper", "key", 30_000, 1_000, 5_000));
        IssuedApiKey issued = service.issue(projectId, "integration", null);
        ApiKey stored = capturedKey(keys);
        when(keys.findByKeyPrefix(stored.getKeyPrefix())).thenReturn(Optional.of(stored));

        ApiKeyCredentials credentials = service.authenticate("Bearer " + issued.secret());

        assertThat(credentials.apiKey()).isSameAs(stored);
        verify(keys, times(1)).findByKeyPrefix(stored.getKeyPrefix());
    }

    private ApiKey capturedKey(ApiKeyRepository repository) {
        ArgumentCaptor<ApiKey> saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }
}

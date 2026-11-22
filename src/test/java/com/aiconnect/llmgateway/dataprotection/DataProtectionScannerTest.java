package com.aiconnect.llmgateway.dataprotection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataProtectionScannerTest {
    private final DataProtectionScanner scanner = new DataProtectionScanner(new ObjectMapper());

    @Test
    void recordsIncompleteInspectionAndRecognizesNestedMedia() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var policy = new EffectiveDataProtectionPolicy(DataProtectionMode.ENFORCE, DataProtectionLevel.STRICT,
                DataProtectionAction.LOCAL_ONLY, false, true, false, false, false, true, List.of(), List.of());
        var longText = mapper.createObjectNode().put("content", "a".repeat(65_537));
        assertThat(scanner.scan(longText, policy).has(DataClassification.SCAN_INCOMPLETE)).isTrue();
        var image = mapper.readTree("{\"image_url\":{\"url\":\"https://example.invalid/image.png\"}}");
        assertThat(scanner.scan(image, policy).has(DataClassification.UNKNOWN_MEDIA)).isTrue();
        var pdf = mapper.createObjectNode().put("file", "data:application/pdf;base64,AAAA");
        assertThat(scanner.scan(pdf, policy).has(DataClassification.UNKNOWN_MEDIA)).isTrue();
        assertThat(scanner.scan(mapper.createObjectNode().put("content", "ordinary text"), policy).hasSensitiveData()).isFalse();
        var deeplyNested = mapper.createObjectNode();
        var current = deeplyNested;
        for (int i = 0; i < 70; i++) current = current.putObject("nested");
        assertThat(scanner.scan(deeplyNested, policy).has(DataClassification.SCAN_INCOMPLETE)).isTrue();
        var largeImage = mapper.createObjectNode().put("url", "data:image/png;base64," + "A".repeat(70_000));
        assertThat(scanner.scan(largeImage, policy).has(DataClassification.UNKNOWN_MEDIA)).isTrue();
        assertThat(scanner.scan(largeImage, policy).has(DataClassification.SCAN_INCOMPLETE)).isFalse();
    }

    @Test
    void detectsSecretPiiFinancialConfidentialAndMediaWithoutReturningMatchedValues() throws Exception {
        var request = new ObjectMapper().readTree("""
                {
                  "model": "text-pro",
                  "messages": [{
                    "role": "user",
                    "content": "email jane.doe@example.com, phone 010-1234-5678, card 4111-1111-1111-1111, confidential"
                  }],
                  "metadata": {"token": "sk-abcdefghijklmnopqrstuvwxyz123456"}
                }
                """);
        EffectiveDataProtectionPolicy policy = new EffectiveDataProtectionPolicy(
                DataProtectionMode.ENFORCE, DataProtectionLevel.STRICT, DataProtectionAction.LOCAL_ONLY,
                false, true, true, true, true, true, List.of(), List.of("PROJECT"));

        DataProtectionScanResult result = scanner.scan(request, policy);

        assertThat(result.classifications()).containsExactlyInAnyOrder(
                DataClassification.SECRET, DataClassification.PII, DataClassification.FINANCIAL,
                DataClassification.CONFIDENTIAL);
        assertThat(result.counts()).containsKeys(DataClassification.SECRET, DataClassification.PII,
                DataClassification.FINANCIAL, DataClassification.CONFIDENTIAL);
        assertThat(result.labels()).containsExactly("CONFIDENTIAL", "FINANCIAL", "PII", "SECRET");
        assertThat(result.toString()).doesNotContain("jane.doe@example.com", "sk-abcdefghijklmnopqrstuvwxyz123456");
    }

    @Test
    void detectsDataUriAndHttpMediaOnlyWhenMediaDetectorIsEnabled() throws Exception {
        var request = new ObjectMapper().readTree("""
                {"messages":[{"role":"user","content":[
                  {"type":"text","text":"see attachment"},
                  {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}
                ]}]}
                """);
        EffectiveDataProtectionPolicy enabled = new EffectiveDataProtectionPolicy(
                DataProtectionMode.MONITOR, DataProtectionLevel.BALANCED, DataProtectionAction.ALLOW,
                true, false, false, false, false, true, List.of(), List.of());
        EffectiveDataProtectionPolicy disabled = new EffectiveDataProtectionPolicy(
                DataProtectionMode.MONITOR, DataProtectionLevel.BALANCED, DataProtectionAction.ALLOW,
                true, false, false, false, false, false, List.of(), List.of());

        assertThat(scanner.scan(request, enabled).has(DataClassification.UNKNOWN_MEDIA)).isTrue();
        assertThat(scanner.scan(request, disabled).has(DataClassification.UNKNOWN_MEDIA)).isFalse();
    }

    @Test
    void appliesCustomPatternsAsConfidentialClassification() throws Exception {
        var request = new ObjectMapper().readTree(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"case-12345\"}]}");
        EffectiveDataProtectionPolicy policy = new EffectiveDataProtectionPolicy(
                DataProtectionMode.ENFORCE, DataProtectionLevel.CUSTOM, DataProtectionAction.BLOCK,
                false, false, false, false, false, false, List.of("case-[0-9]+"), List.of());

        assertThat(scanner.scan(request, policy).has(DataClassification.CONFIDENTIAL)).isTrue();
    }

    @Test
    void detectsModernKeysUserFieldDataUriBypassAttemptsAndResistsReDoS() throws Exception {
        var mapper = new ObjectMapper();
        EffectiveDataProtectionPolicy policy = new EffectiveDataProtectionPolicy(
                DataProtectionMode.ENFORCE, DataProtectionLevel.STRICT, DataProtectionAction.BLOCK,
                false, true, true, false, false, true, List.of("(a+a+)+b"), List.of("CUSTOM"));

        // Modern OpenAI project key prefixed with data:image/png, in content (not strict base64 media field)
        var dataBypass = mapper.createObjectNode()
                .put("content", "data:image/png, sk-proj-AbCdEfGhIjKlMnOpQrStUvWxYz123456");
        assertThat(scanner.scan(dataBypass, policy).has(DataClassification.SECRET)).isTrue();

        // GitHub token placed inside top-level "user" field
        var userFieldSecret = mapper.createObjectNode()
                .put("user", "ghp_1234567890abcdefghijklmnopqrstuvwxyz1234")
                .put("messages", "hello");
        assertThat(scanner.scan(userFieldSecret, policy).has(DataClassification.SECRET)).isTrue();

        // Adversarial backtracking email input completes quickly without O(n^2) hang
        String adversarialEmail = "a.".repeat(10_000) + "noatsign";
        var emailPayload = mapper.createObjectNode().put("content", adversarialEmail);
        long startNanos = System.nanoTime();
        DataProtectionScanResult emailRes = scanner.scan(emailPayload, policy);
        assertThat(emailRes.has(DataClassification.PII)).isFalse();
        assertThat(System.nanoTime() - startNanos).isLessThan(1_000_000_000L);

        // Catastrophic custom regex hits step budget and returns SCAN_INCOMPLETE instead of hanging
        var redosPayload = mapper.createObjectNode().put("content", "a".repeat(80) + "!");
        long redosStart = System.nanoTime();
        DataProtectionScanResult redosRes = scanner.scan(redosPayload, policy);
        assertThat(redosRes.has(DataClassification.SCAN_INCOMPLETE)).isTrue();
        assertThat(System.nanoTime() - redosStart).isLessThan(1_000_000_000L);
    }
}

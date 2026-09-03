package com.aiconnect.llmgateway.dataprotection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataProtectionScannerTest {
    private final DataProtectionScanner scanner = new DataProtectionScanner(new ObjectMapper());

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
}

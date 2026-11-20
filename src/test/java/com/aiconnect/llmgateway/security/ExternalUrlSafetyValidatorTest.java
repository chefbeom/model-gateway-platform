package com.aiconnect.llmgateway.security;

import com.aiconnect.llmgateway.runtime.RuntimeUnavailableException;
import com.aiconnect.llmgateway.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalUrlSafetyValidatorTest {

    @Test
    void allowsPublicAddressesAndNormalizesTrailingSlashes() throws Exception {
        ExternalUrlSafetyValidator validator = new ExternalUrlSafetyValidator(false,
                host -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{(byte) 93, (byte) 184, (byte) 216, 34})});

        assertThat(validator.validateAndNormalizeBaseUrl("  https://api.openai.com/v1///  "))
                .isEqualTo("https://api.openai.com/v1");
        assertThat(validator.validateAndNormalizeBaseUrl("https://93.184.216.34:8443/v1/"))
                .isEqualTo("https://93.184.216.34:8443/v1");
        assertThat(validator.validateAndNormalizeBaseUrl("https://[2606:4700:4700::1111]/v1"))
                .isEqualTo("https://[2606:4700:4700::1111]/v1");
        assertThat(validator.validateAndNormalizeBaseUrl("http://172.15.255.1/v1"))
                .isEqualTo("http://172.15.255.1/v1");
        assertThat(validator.validateAndNormalizeBaseUrl("http://172.32.0.1/v1"))
                .isEqualTo("http://172.32.0.1/v1");
        assertThat(validator.validateAndNormalizeBaseUrl("http://100.63.255.1/v1"))
                .isEqualTo("http://100.63.255.1/v1");
        assertThat(validator.validateAndNormalizeBaseUrl("http://100.128.0.1/v1"))
                .isEqualTo("http://100.128.0.1/v1");

        assertThatCode(() -> validator.assertSafeDestination("https://api.openai.com/v1"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "ftp://api.openai.com/v1",
            "file:///etc/passwd",
            "https://user:pass@api.openai.com/v1",
            "https://@93.184.216.34/v1",
            "https://api.openai.com/v1?token=leak",
            "https://api.openai.com/v1#fragment",
            "https://api.openai.com:0/v1",
            "https://api.openai.com:70000/v1"
    })
    void rejectsMalformedOrCredentialedUrls(String rawUrl) {
        ExternalUrlSafetyValidator validator = new ExternalUrlSafetyValidator(false,
                host -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{8, 8, 8, 8})});

        assertThatThrownBy(() -> validator.validateAndNormalizeBaseUrl(rawUrl))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_PROVIDER_BASE_URL"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/v1",
            "http://127.0.1.1:8080/v1",
            "http://[::1]:8080/v1",
            "http://[0:0:0:0:0:0:0:1]/v1",
            "http://[::ffff:127.0.0.1]/v1",
            "http://[::ffff:7f00:1]/v1",
            "http://localhost/v1",
            "http://LOCALHOST./v1",
            "http://api.localhost/v1",
            "http://0.0.0.0/v1",
            "http://[::]/v1",
            "http://10.0.0.1/v1",
            "http://172.16.0.1/v1",
            "http://172.31.255.254/v1",
            "http://192.168.1.1/v1",
            "http://169.254.169.254/latest/meta-data",
            "http://169.254.0.1/v1",
            "http://[::ffff:169.254.169.254]/v1",
            "http://[0:0:0:0:0:0:169.254.169.254]/v1",
            "http://[fe80::1]/v1",
            "http://[fe80::1%25eth0]/v1",
            "http://100.64.0.1/v1",
            "http://100.100.9.74/v1",
            "http://100.127.255.254/v1",
            "http://[fc00::1]/v1",
            "http://[fd12:3456:789a::1]/v1",
            "http://224.0.0.1/v1",
            "http://[ff02::1]/v1",
            "http://240.0.0.1/v1",
            "http://255.255.255.255/v1",
            "http://mariadb:3306/v1",
            "http://redis/v1",
            "http://api-1:8080/v1",
            "http://metadata/v1",
            "http://gpu-node.local/v1",
            "http://metadata.google.internal/computeMetadata/v1",
            "http://2130706433/v1",
            "http://0x7f000001/v1",
            "http://0177.0.0.1/v1",
            "http://127.1/v1",
            "http://0x7f.0.0.1/v1"
    })
    void blocksUnsafeHostsAndIpRangesByDefault(String url) {
        ExternalUrlSafetyValidator validator = new ExternalUrlSafetyValidator(false,
                host -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{8, 8, 8, 8})});

        assertThatThrownBy(() -> validator.validateAndNormalizeBaseUrl(url))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("EXTERNAL_PROVIDER_URL_FORBIDDEN"));
        assertThatThrownBy(() -> validator.assertSafeDestination(url))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("EXTERNAL_PROVIDER_URL_FORBIDDEN"));
    }

    @Test
    void loopbackTestFlagOnlyPermitsLoopbackLiterals() {
        ExternalUrlSafetyValidator validator = new ExternalUrlSafetyValidator(true,
                host -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{8, 8, 8, 8})});

        assertThat(validator.validateAndNormalizeBaseUrl("http://127.0.0.1:18080/v1"))
                .isEqualTo("http://127.0.0.1:18080/v1");
        assertThat(validator.validateAndNormalizeBaseUrl("http://[::1]:18080/v1"))
                .isEqualTo("http://[::1]:18080/v1");

        for (String stillForbidden : new String[]{
                "http://169.254.169.254/v1",
                "http://10.0.0.1/v1",
                "http://100.64.0.1/v1",
                "http://[fd00::1]/v1",
                "http://0.0.0.0/v1",
                "http://localhost/v1",
                "http://mariadb/v1",
                "http://metadata.google.internal/v1"
        }) {
            assertThatThrownBy(() -> validator.validateAndNormalizeBaseUrl(stillForbidden))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("EXTERNAL_PROVIDER_URL_FORBIDDEN"));
        }
    }

    @Test
    void blocksDnsRebindingAndMixedSafeUnsafeRecordsAtCallTime() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        ExternalUrlSafetyValidator rebindingValidator = new ExternalUrlSafetyValidator(false, host -> {
            if (lookups.incrementAndGet() == 1) {
                return new InetAddress[]{InetAddress.getByAddress(host, new byte[]{(byte) 93, (byte) 184, (byte) 216, 34})};
            }
            return new InetAddress[]{InetAddress.getByAddress(host, new byte[]{(byte) 169, (byte) 254, (byte) 169, (byte) 254})};
        });

        assertThat(rebindingValidator.validateAndNormalizeBaseUrl("https://rebind.example.com/v1"))
                .isEqualTo("https://rebind.example.com/v1");
        assertThatThrownBy(() -> rebindingValidator.assertSafeDestination("https://rebind.example.com/v1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("EXTERNAL_PROVIDER_URL_FORBIDDEN"));

        ExternalUrlSafetyValidator mixedRecords = new ExternalUrlSafetyValidator(false, host -> new InetAddress[]{
                InetAddress.getByAddress(host, new byte[]{8, 8, 8, 8}),
                InetAddress.getByAddress(host, new byte[]{10, 0, 0, 5})
        });
        assertThatThrownBy(() -> mixedRecords.validateAndNormalizeBaseUrl("https://dual.example.com/v1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("EXTERNAL_PROVIDER_URL_FORBIDDEN"));
    }

    @Test
    void allowsUnresolvablePublicTestDomainAtRegistrationButBlocksAtCallTime() {
        ExternalUrlSafetyValidator offlineDns = new ExternalUrlSafetyValidator(false, host -> {
            throw new UnknownHostException(host);
        });

        assertThat(offlineDns.validateAndNormalizeBaseUrl("https://new-provider.test/v1"))
                .isEqualTo("https://new-provider.test/v1");
        assertThatThrownBy(() -> offlineDns.assertSafeDestination("https://new-provider.test/v1"))
                .isInstanceOf(RuntimeUnavailableException.class);
    }
}

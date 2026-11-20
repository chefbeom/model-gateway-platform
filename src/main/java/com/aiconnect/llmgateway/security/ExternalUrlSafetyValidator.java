package com.aiconnect.llmgateway.security;

import com.aiconnect.llmgateway.runtime.RuntimeUnavailableException;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validates external AI provider base URLs and resolved destination IPs to block SSRF
 * against loopback, private networks, link-local metadata, CGNAT/Tailnet, and internal hostnames.
 */
@Component
public class ExternalUrlSafetyValidator {
    private static final String FORBIDDEN_MESSAGE =
            "External provider URL cannot target loopback, private network, link-local, or metadata addresses.";
    private static final Pattern NUMERIC_OR_HEX_LABEL = Pattern.compile("^(0[xX][0-9a-fA-F]+|[0-9]+)$");
    private static final Pattern CANONICAL_DECIMAL_OCTET = Pattern.compile("^(0|[1-9][0-9]{0,2})$");

    @FunctionalInterface
    public interface DnsResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowLoopbackTestServer;
    private final DnsResolver dnsResolver;

    @Autowired
    public ExternalUrlSafetyValidator(
            @Value("${aiconnect.security.external-url.allow-loopback-test-server:false}") boolean allowLoopbackTestServer) {
        this(allowLoopbackTestServer, InetAddress::getAllByName);
    }

    public ExternalUrlSafetyValidator(boolean allowLoopbackTestServer, DnsResolver dnsResolver) {
        this.allowLoopbackTestServer = allowLoopbackTestServer;
        this.dnsResolver = Objects.requireNonNull(dnsResolver, "dnsResolver must not be null");
    }

    /** Validates syntax, host rules, and any resolvable IP addresses at provider registration/update time. */
    public String validateAndNormalizeBaseUrl(String rawUrl) {
        ParsedBaseUrl parsed = parseAndValidateUri(rawUrl);
        validateHostName(parsed.host());
        validateIpOrDns(parsed.host(), false);
        return parsed.normalizedUrl();
    }

    /** Re-validates syntax, host rules, and live DNS resolution immediately before an outbound request. */
    public void assertSafeDestination(String baseUrl) {
        ParsedBaseUrl parsed = parseAndValidateUri(baseUrl);
        validateHostName(parsed.host());
        validateIpOrDns(parsed.host(), true);
    }

    private ParsedBaseUrl parseAndValidateUri(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw invalidUrl();
        }
        String normalized = rawUrl.trim().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(normalized);
            if (uri.getUserInfo() != null
                    || (uri.getRawAuthority() != null && uri.getRawAuthority().contains("@"))
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getPort() == 0
                    || uri.getPort() > 65535) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw invalidUrl();
        }

        if (uri.getHost() == null || uri.getHost().isBlank()) {
            checkOpaqueAuthorityForNonCanonicalIp(uri.getRawAuthority());
            throw invalidUrl();
        }

        String host = uri.getHost().trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.contains("%")) {
            throw forbiddenUrl();
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            throw invalidUrl();
        }
        return new ParsedBaseUrl(normalized, host);
    }

    private void validateHostName(String host) {
        if ("localhost".equals(host)
                || host.endsWith(".localhost")
                || host.endsWith(".local")
                || host.endsWith(".internal")) {
            throw forbiddenUrl();
        }
        if (!host.contains(":")) {
            if (!host.contains(".")) {
                throw forbiddenUrl();
            }
            if (isNonCanonicalNumericIpv4(host)) {
                throw forbiddenUrl();
            }
        }
    }

    private void validateIpOrDns(String host, boolean requireResolution) {
        byte[] ipv4Literal = parseCanonicalIpv4(host);
        if (ipv4Literal != null) {
            try {
                checkAddress(InetAddress.getByAddress(host, ipv4Literal));
            } catch (UnknownHostException impossible) {
                throw forbiddenUrl();
            }
            return;
        }
        if (host.contains(":")) {
            try {
                checkAddress(InetAddress.getByName(host));
            } catch (UnknownHostException ex) {
                throw invalidUrl();
            }
            return;
        }

        InetAddress[] addresses;
        try {
            addresses = dnsResolver.resolve(host);
        } catch (UnknownHostException ex) {
            if (requireResolution) {
                throw new RuntimeUnavailableException("External provider host could not be resolved.", ex);
            }
            return;
        }
        if (addresses == null || addresses.length == 0) {
            if (requireResolution) {
                throw new RuntimeUnavailableException("External provider host resolved to no addresses.",
                        new UnknownHostException(host));
            }
            return;
        }
        for (InetAddress address : addresses) {
            checkAddress(address);
        }
    }

    private void checkAddress(InetAddress address) {
        if (address == null) {
            throw forbiddenUrl();
        }
        if (address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || (address.isLoopbackAddress() && !allowLoopbackTestServer)) {
            throw forbiddenUrl();
        }
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            checkIpv4(raw);
        } else if (raw.length == 16) {
            checkIpv6(raw);
        } else {
            throw forbiddenUrl();
        }
    }

    private void checkIpv4(byte[] addr) {
        int b0 = u(addr[0]);
        int b1 = u(addr[1]);
        int b2 = u(addr[2]);
        if (b0 == 0 || b0 == 10) {
            throw forbiddenUrl();
        }
        if (b0 == 100 && (b1 & 0xC0) == 64) {
            throw forbiddenUrl();
        }
        if (b0 == 127) {
            if (!allowLoopbackTestServer) {
                throw forbiddenUrl();
            }
            return;
        }
        if (b0 == 169 && b1 == 254) {
            throw forbiddenUrl();
        }
        if (b0 == 172 && b1 >= 16 && b1 <= 31) {
            throw forbiddenUrl();
        }
        if (b0 == 192 && b1 == 0 && b2 == 0) {
            throw forbiddenUrl();
        }
        if (b0 == 192 && b1 == 88 && b2 == 99) {
            throw forbiddenUrl();
        }
        if (b0 == 192 && b1 == 168) {
            throw forbiddenUrl();
        }
        if (b0 == 198 && (b1 == 18 || b1 == 19)) {
            throw forbiddenUrl();
        }
        if (b0 >= 224) {
            throw forbiddenUrl();
        }
    }

    private void checkIpv6(byte[] addr) {
        if (isAllZero(addr, 0, 16)) {
            throw forbiddenUrl();
        }
        if (isAllZero(addr, 0, 15) && u(addr[15]) == 1) {
            if (!allowLoopbackTestServer) {
                throw forbiddenUrl();
            }
            return;
        }
        if (isAllZero(addr, 0, 10) && u(addr[10]) == 0xFF && u(addr[11]) == 0xFF) {
            checkIpv4(new byte[]{addr[12], addr[13], addr[14], addr[15]});
            return;
        }
        if (isAllZero(addr, 0, 12)) {
            checkIpv4(new byte[]{addr[12], addr[13], addr[14], addr[15]});
            return;
        }
        int b0 = u(addr[0]);
        int b1 = u(addr[1]);
        int b2 = u(addr[2]);
        int b3 = u(addr[3]);
        int b4 = u(addr[4]);
        int b5 = u(addr[5]);
        if ((b0 & 0xFE) == 0xFC) {
            throw forbiddenUrl();
        }
        if (b0 == 0xFE && ((b1 & 0xC0) == 0x80 || (b1 & 0xC0) == 0xC0)) {
            throw forbiddenUrl();
        }
        if (b0 == 0xFF) {
            throw forbiddenUrl();
        }
        if (b0 == 0x01 && b1 == 0x00 && isAllZero(addr, 2, 8)) {
            throw forbiddenUrl();
        }
        if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) {
            throw forbiddenUrl();
        }
        if (b0 == 0x20 && b1 == 0x02) {
            throw forbiddenUrl();
        }
        if (b0 == 0x00 && b1 == 0x64 && b2 == 0xFF && b3 == 0x9B
                && ((b4 == 0x00 && b5 == 0x00 && isAllZero(addr, 6, 12)) || (b4 == 0x00 && b5 == 0x01))) {
            throw forbiddenUrl();
        }
    }

    private static void checkOpaqueAuthorityForNonCanonicalIp(String rawAuthority) {
        if (rawAuthority == null || rawAuthority.isBlank() || rawAuthority.contains("@") || rawAuthority.startsWith("[")) {
            return;
        }
        String candidate = rawAuthority.trim();
        int colon = candidate.indexOf(':');
        if (colon >= 0) {
            if (candidate.indexOf(':', colon + 1) >= 0) {
                return;
            }
            String portText = candidate.substring(colon + 1);
            if (!portText.matches("^[0-9]+$")) {
                return;
            }
            int port;
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException ex) {
                return;
            }
            if (port < 1 || port > 65535) {
                return;
            }
            candidate = candidate.substring(0, colon);
        }
        while (candidate.endsWith(".")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (!candidate.isEmpty() && isNonCanonicalNumericIpv4(candidate)) {
            throw forbiddenUrl();
        }
    }

    private static boolean isNonCanonicalNumericIpv4(String host) {
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (!NUMERIC_OR_HEX_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return parseCanonicalIpv4(host) == null;
    }

    private static byte[] parseCanonicalIpv4(String host) {
        String[] labels = host.split("\\.", -1);
        if (labels.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!CANONICAL_DECIMAL_OCTET.matcher(labels[i]).matches()) {
                return null;
            }
            int octet = Integer.parseInt(labels[i]);
            if (octet < 0 || octet > 255) {
                return null;
            }
            bytes[i] = (byte) octet;
        }
        return bytes;
    }

    private static boolean isAllZero(byte[] addr, int startInclusive, int endExclusive) {
        for (int i = startInclusive; i < endExclusive; i++) {
            if (addr[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static int u(byte value) {
        return value & 0xFF;
    }

    private static ApiException invalidUrl() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROVIDER_BASE_URL",
                "Provider URL must be an absolute HTTP(S) URL without credentials, query or fragment.");
    }

    private static ApiException forbiddenUrl() {
        return new ApiException(HttpStatus.BAD_REQUEST, "EXTERNAL_PROVIDER_URL_FORBIDDEN", FORBIDDEN_MESSAGE);
    }

    private record ParsedBaseUrl(String normalizedUrl, String host) { }
}

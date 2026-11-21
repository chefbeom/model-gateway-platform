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
        ParsedBaseUrl parsed = parseAndValidateUri(rawUrl, false);
        validateHostName(parsed.host(), false);
        validateIpOrDns(parsed.host(), false, false);
        return parsed.normalizedUrl();
    }

    /** Re-validates syntax, host rules, and live DNS resolution immediately before an outbound request. */
    public void assertSafeDestination(String baseUrl) {
        ParsedBaseUrl parsed = parseAndValidateUri(baseUrl, false);
        validateHostName(parsed.host(), false);
        validateIpOrDns(parsed.host(), true, false);
    }

    /** Validates runtime endpoint URLs while allowing private/LAN/Tailnet hosts and blocking cloud metadata and link-local targets. */
    public String validateAndNormalizeRuntimeBaseUrl(String rawUrl) {
        ParsedBaseUrl parsed = parseAndValidateUri(rawUrl, true);
        validateHostName(parsed.host(), true);
        validateIpOrDns(parsed.host(), false, true);
        return parsed.normalizedUrl();
    }

    /** Re-validates runtime endpoint URLs immediately before making an outbound probe or inference request. */
    public void assertSafeRuntimeDestination(String baseUrl) {
        ParsedBaseUrl parsed = parseAndValidateUri(baseUrl, true);
        validateHostName(parsed.host(), true);
        validateIpOrDns(parsed.host(), false, true);
    }

    public String validateTelegramBotToken(String rawToken) {
        String token = rawToken == null ? "" : rawToken.trim();
        if (token.isEmpty() || token.length() > 128
                || !token.matches("^[A-Za-z0-9:_-]+$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_NOTIFICATION_SECRET",
                    "Telegram bot token contains invalid characters.");
        }
        return token;
    }

    public String validateTelegramChatId(String rawChatId) {
        String chatId = rawChatId == null ? "" : rawChatId.trim();
        if (chatId.isEmpty() || chatId.length() > 128 || chatId.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_NOTIFICATION_TARGET",
                    "Telegram chat target must be a valid chat identifier.");
        }
        return chatId;
    }

    private ParsedBaseUrl parseAndValidateUri(String rawUrl, boolean runtimeMode) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw invalidUrl(runtimeMode);
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
            throw invalidUrl(runtimeMode);
        }

        if (uri.getHost() == null || uri.getHost().isBlank()) {
            checkOpaqueAuthorityForNonCanonicalIp(uri.getRawAuthority(), runtimeMode);
            throw invalidUrl(runtimeMode);
        }

        String host = uri.getHost().trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.contains("%")) {
            throw forbiddenUrl(runtimeMode);
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            throw invalidUrl(runtimeMode);
        }
        return new ParsedBaseUrl(normalized, host);
    }

    private void validateHostName(String host, boolean runtimeMode) {
        if ("metadata.google.internal".equals(host) || "metadata.goog".equals(host) || "instance-data".equals(host)) {
            throw forbiddenUrl(runtimeMode);
        }
        if (!runtimeMode && ("localhost".equals(host)
                || host.endsWith(".localhost")
                || host.endsWith(".local")
                || host.endsWith(".internal"))) {
            throw forbiddenUrl(false);
        }
        if (!host.contains(":")) {
            if (!runtimeMode && !host.contains(".")) {
                throw forbiddenUrl(false);
            }
            if (isNonCanonicalNumericIpv4(host)) {
                throw forbiddenUrl(runtimeMode);
            }
        }
    }

    private void validateIpOrDns(String host, boolean requireResolution, boolean runtimeMode) {
        byte[] ipv4Literal = parseCanonicalIpv4(host);
        if (ipv4Literal != null) {
            try {
                checkAddress(InetAddress.getByAddress(host, ipv4Literal), runtimeMode);
            } catch (UnknownHostException impossible) {
                throw forbiddenUrl(runtimeMode);
            }
            return;
        }
        if (host.contains(":")) {
            try {
                checkAddress(InetAddress.getByName(host), runtimeMode);
            } catch (UnknownHostException ex) {
                throw invalidUrl(runtimeMode);
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
            checkAddress(address, runtimeMode);
        }
    }

    private void checkAddress(InetAddress address, boolean runtimeMode) {
        if (address == null) {
            throw forbiddenUrl(runtimeMode);
        }
        if (address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()
                || (!runtimeMode && (address.isSiteLocalAddress()
                        || (address.isLoopbackAddress() && !allowLoopbackTestServer)))) {
            throw forbiddenUrl(runtimeMode);
        }
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            checkIpv4(raw, runtimeMode);
        } else if (raw.length == 16) {
            checkIpv6(raw, runtimeMode);
        } else {
            throw forbiddenUrl(runtimeMode);
        }
    }

    private void checkIpv4(byte[] addr, boolean runtimeMode) {
        int b0 = u(addr[0]);
        int b1 = u(addr[1]);
        int b2 = u(addr[2]);
        if (b0 == 0) {
            throw forbiddenUrl(runtimeMode);
        }
        if (b0 == 169 && b1 == 254) {
            throw forbiddenUrl(runtimeMode);
        }
        if (b0 >= 224) {
            throw forbiddenUrl(runtimeMode);
        }
        if (runtimeMode) {
            return;
        }
        if (b0 == 10) {
            throw forbiddenUrl(false);
        }
        if (b0 == 100 && (b1 & 0xC0) == 64) {
            throw forbiddenUrl(false);
        }
        if (b0 == 127) {
            if (!allowLoopbackTestServer) {
                throw forbiddenUrl(false);
            }
            return;
        }
        if (b0 == 172 && b1 >= 16 && b1 <= 31) {
            throw forbiddenUrl(false);
        }
        if (b0 == 192 && b1 == 0 && b2 == 0) {
            throw forbiddenUrl(false);
        }
        if (b0 == 192 && b1 == 88 && b2 == 99) {
            throw forbiddenUrl(false);
        }
        if (b0 == 192 && b1 == 168) {
            throw forbiddenUrl(false);
        }
        if (b0 == 198 && (b1 == 18 || b1 == 19)) {
            throw forbiddenUrl(false);
        }
    }

    private void checkIpv6(byte[] addr, boolean runtimeMode) {
        if (isAllZero(addr, 0, 16)) {
            throw forbiddenUrl(runtimeMode);
        }
        if (isAllZero(addr, 0, 10) && u(addr[10]) == 0xFF && u(addr[11]) == 0xFF) {
            checkIpv4(new byte[]{addr[12], addr[13], addr[14], addr[15]}, runtimeMode);
            return;
        }
        if (isAllZero(addr, 0, 12) && !isAllZero(addr, 12, 16)) {
            if (isAllZero(addr, 0, 15) && u(addr[15]) == 1) {
                if (!runtimeMode && !allowLoopbackTestServer) {
                    throw forbiddenUrl(false);
                }
                return;
            }
            checkIpv4(new byte[]{addr[12], addr[13], addr[14], addr[15]}, runtimeMode);
            return;
        }
        int b0 = u(addr[0]);
        int b1 = u(addr[1]);
        int b2 = u(addr[2]);
        int b3 = u(addr[3]);
        int b4 = u(addr[4]);
        int b5 = u(addr[5]);
        // Block AWS IPv6 metadata endpoint fd00:ec2::254 even in runtime mode
        if (b0 == 0xFD && b1 == 0x00 && b2 == 0x0E && b3 == 0xC2
                && isAllZero(addr, 4, 14) && u(addr[14]) == 0x02 && u(addr[15]) == 0x54) {
            throw forbiddenUrl(runtimeMode);
        }
        if (b0 == 0xFE && ((b1 & 0xC0) == 0x80 || (b1 & 0xC0) == 0xC0)) {
            throw forbiddenUrl(runtimeMode);
        }
        if (b0 == 0xFF) {
            throw forbiddenUrl(runtimeMode);
        }
        if (runtimeMode) {
            return;
        }
        if ((b0 & 0xFE) == 0xFC) {
            throw forbiddenUrl(false);
        }
        if (b0 == 0x01 && b1 == 0x00 && isAllZero(addr, 2, 8)) {
            throw forbiddenUrl(false);
        }
        if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) {
            throw forbiddenUrl(false);
        }
        if (b0 == 0x20 && b1 == 0x02) {
            throw forbiddenUrl(false);
        }
        if (b0 == 0x00 && b1 == 0x64 && b2 == 0xFF && b3 == 0x9B
                && ((b4 == 0x00 && b5 == 0x00 && isAllZero(addr, 6, 12)) || (b4 == 0x00 && b5 == 0x01))) {
            throw forbiddenUrl(false);
        }
    }

    private static void checkOpaqueAuthorityForNonCanonicalIp(String rawAuthority, boolean runtimeMode) {
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
            throw forbiddenUrl(runtimeMode);
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

    private static ApiException invalidUrl(boolean runtimeMode) {
        if (runtimeMode) {
            return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RUNTIME_BASE_URL",
                    "The runtime Base URL must be an absolute HTTP(S) URL without credentials, query, or fragment.");
        }
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROVIDER_BASE_URL",
                "Provider URL must be an absolute HTTP(S) URL without credentials, query or fragment.");
    }

    private static ApiException forbiddenUrl(boolean runtimeMode) {
        if (runtimeMode) {
            return new ApiException(HttpStatus.BAD_REQUEST, "RUNTIME_ENDPOINT_URL_FORBIDDEN",
                    "Runtime endpoint URL cannot target cloud metadata, link-local, or unspecified addresses.");
        }
        return new ApiException(HttpStatus.BAD_REQUEST, "EXTERNAL_PROVIDER_URL_FORBIDDEN", FORBIDDEN_MESSAGE);
    }

    private record ParsedBaseUrl(String normalizedUrl, String host) { }
}

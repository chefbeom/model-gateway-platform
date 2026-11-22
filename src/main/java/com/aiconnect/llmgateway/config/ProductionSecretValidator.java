package com.aiconnect.llmgateway.config;

import com.aiconnect.llmgateway.identity.AuthProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class ProductionSecretValidator implements InitializingBean {
    private final GatewayProperties gateway;
    private final AuthProperties auth;
    private final Environment environment;
    private final boolean allowInsecureDevelopmentSecrets;

    public ProductionSecretValidator(GatewayProperties gateway, AuthProperties auth, Environment environment,
                                     @Value("${ALLOW_INSECURE_DEVELOPMENT_SECRETS:false}") boolean allowInsecureDevelopmentSecrets) {
        this.gateway = gateway;
        this.auth = auth;
        this.environment = environment;
        this.allowInsecureDevelopmentSecrets = allowInsecureDevelopmentSecrets;
    }

    @Override
    public void afterPropertiesSet() {
        if (allowInsecureDevelopmentSecrets || environment.acceptsProfiles(Profiles.of("integration", "test"))) return;
        if ("true".equalsIgnoreCase(environment.getProperty("aiconnect.security.external-url.allow-loopback-test-server"))) {
            throw new IllegalStateException("aiconnect.security.external-url.allow-loopback-test-server must be false outside test profiles.");
        }
        String dbPassword = environment.getProperty("spring.datasource.password");
        if ("aiconnect".equalsIgnoreCase(dbPassword)) {
            throw new IllegalStateException("DB_PASSWORD must not use the default password in production.");
        }
        String sharedStateProvider = environment.getProperty("aiconnect.deployment.shared-state-provider");
        if ("REDIS".equalsIgnoreCase(sharedStateProvider)) {
            String redisPassword = environment.getProperty("spring.data.redis.password");
            if (redisPassword == null || redisPassword.isBlank() || redisPassword.length() < 12
                    || redisPassword.toLowerCase(Locale.ROOT).contains("change-me")) {
                throw new IllegalStateException("REDIS_PASSWORD must be set to a strong password when Redis shared state is enabled.");
            }
        }
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("ADMIN_API_TOKEN", gateway.adminToken());
        secrets.put("API_KEY_PEPPER", gateway.apiKeyPepper());
        secrets.put("GATEWAY_ENCRYPTION_KEY", gateway.encryptionKey());
        secrets.put("AUTH_SIGNING_KEY", auth.signingKey());
        secrets.put("AUTH_REFRESH_PEPPER", auth.refreshPepper());
        String insecure = secrets.entrySet().stream()
                .filter(entry -> unsafe(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        if (insecure != null) {
            throw new IllegalStateException(insecure + " must be set to a non-default secret before the application can start.");
        }
    }

    private boolean unsafe(String value) {
        if (value == null || value.isBlank() || value.length() < 32) return true;
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("change-me")
                || normalized.contains("development-only")
                || normalized.contains("replace-with")
                || normalized.contains("placeholder")
                || normalized.equals("validation");
    }
}

package com.aiconnect.llmgateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_security_autoconfig;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class SecurityAutoConfigurationIntegrationTest {
    @Autowired org.springframework.context.ApplicationContext context;

    @Test
    void doesNotCreateUnusedGeneratedPasswordUserStore() {
        Map<String, UserDetailsService> services = context.getBeansOfType(UserDetailsService.class);
        assertThat(services).isEmpty();
    }
}

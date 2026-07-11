package com.aiconnect.llmgateway.config;

import com.aiconnect.llmgateway.web.AdminTokenFilter;
import com.aiconnect.llmgateway.web.OrganizationAuthorizationFilter;
import com.aiconnect.llmgateway.web.SessionAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityFilterRegistrationConfig {
    @Bean FilterRegistrationBean<SessionAuthenticationFilter> disableSessionAuthenticationFilterRegistration(SessionAuthenticationFilter filter) { return disabled(filter); }
    @Bean FilterRegistrationBean<AdminTokenFilter> disableAdminTokenFilterRegistration(AdminTokenFilter filter) { return disabled(filter); }
    @Bean FilterRegistrationBean<OrganizationAuthorizationFilter> disableOrganizationAuthorizationFilterRegistration(OrganizationAuthorizationFilter filter) { return disabled(filter); }
    private <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> disabled(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}

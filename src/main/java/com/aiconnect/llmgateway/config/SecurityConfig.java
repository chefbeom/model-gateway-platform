package com.aiconnect.llmgateway.config;

import com.aiconnect.llmgateway.web.AdminTokenFilter;
import com.aiconnect.llmgateway.web.OrganizationAuthorizationFilter;
import com.aiconnect.llmgateway.web.SessionAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SessionAuthenticationFilter sessionAuthenticationFilter,
                                            AdminTokenFilter adminTokenFilter, OrganizationAuthorizationFilter organizationAuthorizationFilter) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .addFilterBefore(sessionAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(adminTokenFilter, SessionAuthenticationFilter.class)
                .addFilterAfter(organizationAuthorizationFilter, AdminTokenFilter.class)
                .build();
    }
}

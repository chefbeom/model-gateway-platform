package com.aiconnect.llmgateway.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class FilterRegistrationConfig {
    @Bean
    FilterRegistrationBean<StreamingChatFilter> streamingChatFilterRegistration(StreamingChatFilter filter) {
        FilterRegistrationBean<StreamingChatFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }
}

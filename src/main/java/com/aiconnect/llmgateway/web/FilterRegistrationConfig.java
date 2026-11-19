package com.aiconnect.llmgateway.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterRegistrationConfig {
    @Bean
    FilterRegistrationBean<com.aiconnect.llmgateway.retention.ContentRetentionFilter> contentRetentionRegistration(com.aiconnect.llmgateway.retention.ContentRetentionFilter filter) {
        var registration=new FilterRegistrationBean<>(filter);
        registration.setOrder(com.aiconnect.llmgateway.retention.ContentRetentionFilter.FILTER_ORDER);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST,jakarta.servlet.DispatcherType.ASYNC);
        return registration;
    }
    @Bean
    FilterRegistrationBean<StreamingChatFilter> streamingChatFilterRegistration(StreamingChatFilter filter) {
        FilterRegistrationBean<StreamingChatFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(StreamingChatFilter.FILTER_ORDER);
        registration.setAsyncSupported(true);
        return registration;
    }
}

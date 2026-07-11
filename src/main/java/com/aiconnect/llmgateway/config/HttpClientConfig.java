package com.aiconnect.llmgateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.Proxy;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class HttpClientConfig {
    @Bean
    RestClient runtimeRestClient(GatewayProperties properties, RuntimeProxySettings proxySettings) {
        SimpleClientHttpRequestFactory factory = requestFactory(properties);
        if (proxySettings.enabled()) {
            factory.setProxy(new Proxy(Proxy.Type.HTTP, proxySettings.address()));
        }
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean
    RestClient notificationRestClient(GatewayProperties properties) {
        return RestClient.builder().requestFactory(requestFactory(properties)).build();
    }

    private SimpleClientHttpRequestFactory requestFactory(GatewayProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeoutMs());
        factory.setReadTimeout(properties.responseTimeoutMs());
        return factory;
    }
}

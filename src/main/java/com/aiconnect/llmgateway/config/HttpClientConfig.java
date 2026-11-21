package com.aiconnect.llmgateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class HttpClientConfig {
    @Bean
    RestClient runtimeRestClient(GatewayProperties properties, RuntimeProxySettings proxySettings) {
        SimpleClientHttpRequestFactory factory = noRedirectRequestFactory(properties);
        if (proxySettings.enabled()) {
            factory.setProxy(new Proxy(Proxy.Type.HTTP, proxySettings.address()));
        }
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean
    RestClient notificationRestClient(GatewayProperties properties) {
        return RestClient.builder().requestFactory(noRedirectRequestFactory(properties)).build();
    }

    @Bean
    RestClient externalProviderRestClient(GatewayProperties properties) {
        return RestClient.builder().requestFactory(noRedirectRequestFactory(properties)).build();
    }

    private SimpleClientHttpRequestFactory noRedirectRequestFactory(GatewayProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(properties.connectTimeoutMs());
        factory.setReadTimeout(properties.responseTimeoutMs());
        return factory;
    }
}

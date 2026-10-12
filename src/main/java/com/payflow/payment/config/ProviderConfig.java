package com.payflow.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ProviderProperties.class)
public class ProviderConfig {
    @Bean("providerARestClient")
    public RestClient providerARestClient(
            RestClient.Builder builder, ProviderProperties properties) {
        return client(builder, properties);
    }

    @Bean("providerBRestClient")
    public RestClient providerBRestClient(
            RestClient.Builder builder, ProviderProperties properties) {
        return client(builder, properties);
    }

    private RestClient client(RestClient.Builder builder, ProviderProperties properties) {
        if (properties.getConnectTimeout().toMillis() < 1
                || properties.getReadTimeout().toMillis() < 1) {
            throw new IllegalArgumentException("Provider connect/read timeouts must be positive");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout());
        return builder.clone().requestFactory(factory).build();
    }
}

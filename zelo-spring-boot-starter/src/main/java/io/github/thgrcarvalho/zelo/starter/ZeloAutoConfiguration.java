package io.github.thgrcarvalho.zelo.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * Auto-configuration for the Zelo starter. Wires the callback client and the
 * webhook handler dispatcher unconditionally; the signature validator and the
 * receiver endpoint only when {@code zelo.webhook-secret} is configured (so an
 * app that does not receive webhooks never exposes an unverified endpoint).
 */
@AutoConfiguration
@EnableConfigurationProperties(ZeloProperties.class)
public class ZeloAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ZeloClient zeloClient(RestClient.Builder restClientBuilder, ZeloProperties properties) {
        return new ZeloClient(restClientBuilder, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ZeloWebhookDispatcher zeloWebhookDispatcher(ConfigurableListableBeanFactory beanFactory) {
        return new ZeloWebhookDispatcher(beanFactory);
    }

    @Bean
    @ConditionalOnProperty(prefix = "zelo", name = "webhook-secret")
    @ConditionalOnMissingBean
    public ZeloWebhookValidator zeloWebhookValidator(ZeloProperties properties) {
        return new ZeloWebhookValidator(properties.getWebhookSecret());
    }

    @Bean
    @ConditionalOnProperty(prefix = "zelo", name = "webhook-secret")
    @ConditionalOnMissingBean
    public ZeloWebhookController zeloWebhookController(ZeloWebhookValidator validator,
                                                      ZeloWebhookDispatcher dispatcher,
                                                      ZeloClient client, ObjectMapper objectMapper,
                                                      ZeloProperties properties) {
        return new ZeloWebhookController(validator, dispatcher, client, objectMapper, properties);
    }
}

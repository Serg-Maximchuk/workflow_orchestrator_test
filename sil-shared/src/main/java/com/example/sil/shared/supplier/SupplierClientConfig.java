package com.example.sil.shared.supplier;

import com.example.sil.shared.correlation.CorrelationContext;
import com.example.sil.shared.correlation.CorrelationIdFilter;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} used for every outbound supplier call.
 *
 * <p>Three things are wired in here, and each one exists because of a specific failure mode:
 * timeouts (a hanging supplier must become a failing call, not a stuck thread), the correlation
 * header (so one order journey is greppable in our logs and in the supplier's request journal),
 * and an OAuth2 client-credentials token fetched and cached per request.
 */
@Configuration
@EnableConfigurationProperties(SupplierProperties.class)
public class SupplierClientConfig {

    /**
     * Client-credentials token manager for a service-to-service call: there is no logged-in user,
     * so the token is bound to the application itself rather than to a security context.
     */
    @Bean
    OAuth2AuthorizedClientManager supplierAuthorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientService);
        // The provider caches the token and only asks the authorization server again once it is
        // close to expiry, so a burst of supplier calls costs one token request, not one each.
        manager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    @Bean
    RestClient supplierRestClient(
            SupplierProperties properties, OAuth2AuthorizedClientManager authorizedClientManager) {

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders()
                            .set(CorrelationIdFilter.HEADER, CorrelationContext.currentOrNew());
                    accessToken(authorizedClientManager, properties)
                            .ifPresent(token -> request.getHeaders().setBearerAuth(token));
                    return execution.execute(request, body);
                })
                .build();
    }

    private java.util.Optional<String> accessToken(
            OAuth2AuthorizedClientManager manager, SupplierProperties properties) {

        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
                .withClientRegistrationId(properties.getClientRegistrationId())
                .principal(properties.getClientRegistrationId())
                .build();

        OAuth2AuthorizedClient client = manager.authorize(request);
        return java.util.Optional.ofNullable(client)
                .map(OAuth2AuthorizedClient::getAccessToken)
                .map(token -> token.getTokenValue());
    }
}

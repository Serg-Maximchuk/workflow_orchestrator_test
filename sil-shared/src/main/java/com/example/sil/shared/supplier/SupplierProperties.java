package com.example.sil.shared.supplier;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the external VOIP and hardware supplier.
 *
 * <p>The timeouts are not decoration. An integration without a read timeout inherits the slowest
 * behaviour of the slowest downstream system: threads pile up waiting, and the retry and circuit
 * breaker below never get a chance to act because nothing ever fails. They only start working once
 * a slow call is turned into a failed call.
 */
@ConfigurationProperties(prefix = "sil.supplier.voip")
public class SupplierProperties {

    /** Base URL of the supplier API (WireMock during development). */
    private String baseUrl = "http://localhost:8081";

    /** How long to wait for the TCP connection. */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** How long to wait for the response once connected. */
    private Duration readTimeout = Duration.ofSeconds(3);

    /** Name of the OAuth2 client registration used for outbound calls. */
    private String clientRegistrationId = "voip-supplier";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public String getClientRegistrationId() {
        return clientRegistrationId;
    }

    public void setClientRegistrationId(String clientRegistrationId) {
        this.clientRegistrationId = clientRegistrationId;
    }
}

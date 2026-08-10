package com.example.sil.shared.supplier;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection settings for the external VOIP and hardware supplier.
 *
 * <p>A record rather than a bean with setters: configuration is read once at startup and never
 * changes afterwards, so constructor binding both removes the boilerplate and makes the object
 * immutable. Defaults live in {@code @DefaultValue} instead of field initialisers.
 *
 * <p>The timeouts are not decoration. An integration without a read timeout inherits the slowest
 * behaviour of the slowest downstream system: threads pile up waiting, and the retry and circuit
 * breaker never get a chance to act because nothing ever fails. They only start working once a
 * slow call is turned into a failed call.
 *
 * @param baseUrl base URL of the supplier API (WireMock during development)
 * @param connectTimeout how long to wait for the TCP connection
 * @param readTimeout how long to wait for the response once connected
 * @param clientRegistrationId OAuth2 client registration used for outbound calls
 */
@ConfigurationProperties(prefix = "sil.supplier.voip")
public record SupplierProperties(
        @DefaultValue("http://localhost:8081") String baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("3s") Duration readTimeout,
        @DefaultValue("voip-supplier") String clientRegistrationId) {}

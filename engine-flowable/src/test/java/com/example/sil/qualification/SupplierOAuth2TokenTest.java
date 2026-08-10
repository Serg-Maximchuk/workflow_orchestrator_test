package com.example.sil.qualification;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Outbound OAuth2 client credentials, in a context of its own because the token cache is
 * application-wide state: sharing a context with the other tests would make the assertion about
 * how many token requests were made depend on which test happened to run first.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:oauth;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "sil.messaging.enabled=false",
        "flowable.async-executor-activate=false"
})
@AutoConfigureMockMvc
class SupplierOAuth2TokenTest {

    private static final String QUALIFY_URL =
            "/tmf-api/serviceQualification/v5/checkServiceQualification";
    private static final String AVAILABILITY_PATH = "/supplier/v1/availability";
    private static final String TOKEN_PATH = "/supplier/oauth/token";

    private static WireMockServer supplier;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void startSupplierStub() {
        supplier = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        supplier.start();
        supplier.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(okJson("""
                {"access_token":"stub-supplier-access-token",
                 "token_type":"Bearer","expires_in":3600}""")));
        supplier.stubFor(post(urlPathEqualTo(AVAILABILITY_PATH)).willReturn(okJson("""
                {"available":true,"offeredServiceSpecIds":["VOIP_BUSINESS"],"maxSpeedMbps":900}""")));
    }

    @AfterAll
    static void stopSupplierStub() {
        supplier.stop();
    }

    @DynamicPropertySource
    static void supplierProperties(DynamicPropertyRegistry registry) {
        registry.add("sil.supplier.voip.base-url", () -> supplier.baseUrl());
        registry.add("spring.security.oauth2.client.provider.voip-supplier.token-uri",
                () -> supplier.baseUrl() + TOKEN_PATH);
    }

    @Test
    @DisplayName("fetches a client-credentials token once and reuses it across supplier calls")
    void fetchesTokenOnceAndReusesIt() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(MockMvcRequestBuilders.post(QUALIFY_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"externalId":"OMS-4711",
                                     "place":{"postcode":"SW1A 1AA"},
                                     "serviceSpecId":"VOIP_BUSINESS",
                                     "requestedSpeedMbps":100}"""))
                    .andExpect(MockMvcResultMatchers.status().isCreated());
        }

        supplier.verify(exactly(3), postRequestedFor(urlPathEqualTo(AVAILABILITY_PATH))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer stub-supplier-access-token")));

        // The token is valid for an hour, so three business calls must not cost three token calls.
        supplier.verify(exactly(1), postRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withRequestBody(containing("grant_type=client_credentials")));
    }
}

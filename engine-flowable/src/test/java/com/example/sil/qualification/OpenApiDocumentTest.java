package com.example.sil.qualification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Checks that the OpenAPI document actually describes the API, and writes it to
 * {@code build/openapi.json} so CI can publish it as an artifact.
 *
 * <p>Exporting it from a test rather than from a running server means the contract is regenerated
 * on every build, and a change to it shows up as a diff in review instead of being discovered by
 * whoever integrates against it.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:openapi;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "sil.messaging.enabled=false",
        "flowable.async-executor-activate=false"
})
@AutoConfigureMockMvc
class OpenApiDocumentTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("publishes an OpenAPI document covering the qualification API")
    void exportsOpenApiDocument() throws Exception {
        String document = mockMvc.perform(MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(document)
                .contains("/tmf-api/serviceQualification/v5/checkServiceQualification")
                .contains("CheckServiceQualificationRequest")
                .contains("Idempotency-Key");

        Path target = Path.of("build", "openapi.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, document, StandardCharsets.UTF_8);
    }
}

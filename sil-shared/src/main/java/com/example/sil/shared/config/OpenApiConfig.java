package com.example.sil.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document served at {@code /v3/api-docs} with Swagger UI at {@code /swagger-ui.html}.
 * CI stores the generated document as a build artifact, which makes contract changes visible in
 * review rather than only at integration time.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI silOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Service Integration Layer")
                .version("v1")
                .description("""
                        Telecom service integration layer: service qualification, service ordering \
                        and supplier orchestration. The API follows TM Forum Open APIs where \
                        practical; every deviation is recorded in docs/variance-log.md.""")
                .license(new License().name("Learning project")));
    }
}

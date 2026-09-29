package io.flakehunter.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 3 description served at /v3/api-docs, with an interactive UI at /swagger-ui.html. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI flakeHunterOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FlakeHunter API")
                        .version("v1")
                        .description("Ingest JUnit reports from CI, detect flaky tests, and manage quarantine.")
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes("apiKey", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-API-Key")));
    }
}

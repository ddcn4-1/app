package org.ddcn41.ticketing_system.global.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Ticketing System API",
                version = "0.0.1",
                description = "APIs for ticketing system"
        ),
        servers = {
                @Server(url = "/", description = "api.domain.com")
        }
)
public class SwaggerConfig {
}


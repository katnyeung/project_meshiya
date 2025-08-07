package com.meshiya.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Swagger/OpenAPI 3 configuration for the Meshiya AI Midnight Diner API
 */
@Configuration
public class SwaggerConfig {

    @Value("${server.port:8080}")
    private int serverPort;

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Meshiya AI Midnight Diner API")
                        .version("1.0.0")
                        .description("REST API documentation for the AI Midnight Diner project - a 2.5D chatroom " +
                                   "inspired by Shin'ya Shokudō where users sit at bar seats, chat with an AI Master, " +
                                   "and order virtual drinks and food.")
                        .contact(new Contact()
                                .name("Meshiya Team")
                                .url("https://github.com/meshiya/project_meshiya")
                                .email("support@meshiya.com"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort)
                                .description("Local Development Server"),
                        new Server()
                                .url("https://api.meshiya.com")
                                .description("Production Server")
                ));
    }
}
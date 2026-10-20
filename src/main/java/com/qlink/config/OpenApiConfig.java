package com.qlink.config;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("qLink API").version("v1"))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
    @Bean
    OpenApiCustomizer commonErrors() {
        return api -> api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
            for (String status : new String[]{"400", "401", "403", "404", "409", "500"}) {
                operation.getResponses().addApiResponse(status, new ApiResponse().description(switch (status) {
                    case "400" -> "Invalid request or unsupported provider";
                    case "401" -> "Invalid, expired, revoked, or reused token; invalid nonce";
                    case "403" -> "Access denied";
                    case "404" -> "Identity not registered or resource not found";
                    case "409" -> "Identity already registered";
                    default -> "Internal server error";
                }).content(new Content().addMediaType("application/json", new MediaType()
                        .schema(new ObjectSchema().addProperty("success", new BooleanSchema())
                                .addProperty("message", new StringSchema()).addProperty("data", new ObjectSchema()))
                        .example(java.util.Map.of("success", false, "message", "Request failed",
                                "data", java.util.Map.of("code", "ERROR_CODE", "fields", java.util.Map.of()))))));
            }
        }));
    }
}

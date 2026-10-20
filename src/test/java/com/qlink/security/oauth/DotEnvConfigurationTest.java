package com.qlink.security.oauth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DotEnvConfigurationTest {
    private static final String CLIENT_ID =
            "66607447341-57pg2483cp73jo2geh525sa1ucm1b7os.apps.googleusercontent.com";
    @TempDir Path directory;

    private void assertAudience(Map<String, Object> osVariables, String... expected) throws Exception {
        // Use the real application config with only the import path isolated from local secrets.
        String yaml = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);
        assertThat(yaml).contains("optional:file:./.env[.properties]");
        Path config = directory.resolve("application.yml");
        Files.writeString(config, yaml.replace("file:./.env", directory.resolve(".env").toUri().toString()));
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove("systemProperties");
        environment.getPropertySources().replace("systemEnvironment",
                new SystemEnvironmentPropertySource("systemEnvironment", osVariables));
        var app = new SpringApplication(PropertiesConfig.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setEnvironment(environment);
        app.setLogStartupInfo(false);
        app.setRegisterShutdownHook(false);
        try (var context = app.run("--spring.config.location=" + config.toUri(), "--spring.main.banner-mode=off")) {
            assertThat(context.getBean(OAuthProperties.class).googleClientIds()).containsExactly(expected);
        }
    }

    @Test void importsLocalDotEnvAsProperties() throws Exception {
        Files.writeString(directory.resolve(".env"), "GOOGLE_CLIENT_ID=" + CLIENT_ID + "\n");
        assertAudience(Map.of(), CLIENT_ID);
    }

    @Test void operatingSystemEnvironmentOverridesDotEnv() throws Exception {
        Files.writeString(directory.resolve(".env"), "GOOGLE_CLIENT_ID=local-client\n");
        assertAudience(Map.of("GOOGLE_CLIENT_ID", CLIENT_ID), CLIENT_ID);
    }

    @Test void deploymentDoesNotRequireDotEnv() throws Exception {
        assertAudience(Map.of("GOOGLE_CLIENT_ID", CLIENT_ID), CLIENT_ID);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OAuthProperties.class)
    static class PropertiesConfig {}
}

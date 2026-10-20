package com.qlink.security.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.io.UncheckedIOException;
import static org.assertj.core.api.Assertions.assertThat;

class OAuthConfigurationTest {
    private static final String WEB_CLIENT_ID =
            "66607447341-57pg2483cp73jo2geh525sa1ucm1b7os.apps.googleusercontent.com";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class)
            .withInitializer(context -> {
                // Isolate tests from the developer's exported OAuth variables.
                context.getEnvironment().getPropertySources().remove("systemEnvironment");
                context.getEnvironment().getPropertySources().remove("systemProperties");
                try {
                    new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                            .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });

    @Test
    void singularEnvironmentVariableBindsToTheVerifierProperties() {
        runner.withPropertyValues("GOOGLE_CLIENT_ID=" + WEB_CLIENT_ID, "GOOGLE_CLIENT_IDS=legacy-client")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OAuthProperties.class).googleClientIds()).containsExactly(WEB_CLIENT_ID);
                    assertThat(context.getEnvironment().containsProperty("app.oauth.google.client-id")).isFalse();
                });
    }

    @Test
    void legacyPluralVariableStillWorks() {
        runner.withPropertyValues("GOOGLE_CLIENT_IDS=" + WEB_CLIENT_ID)
                .run(context -> assertThat(context.getBean(OAuthProperties.class).googleClientIds())
                        .containsExactly(WEB_CLIENT_ID));
    }

    @Test
    void androidClientIdsBindSeparatelyFromWebAudience() {
        runner.withPropertyValues("GOOGLE_CLIENT_ID=" + WEB_CLIENT_ID,
                        "GOOGLE_ANDROID_CLIENT_IDS=123-debug.apps.googleusercontent.com,123-release.apps.googleusercontent.com")
                .run(context -> {
                    var properties = context.getBean(OAuthProperties.class);
                    assertThat(properties.googleClientIds()).containsExactly(WEB_CLIENT_ID);
                    assertThat(properties.googleAndroidClientIds()).containsExactly(
                            "123-debug.apps.googleusercontent.com", "123-release.apps.googleusercontent.com");
                });
    }

    @Test
    void missingConfigurationDoesNotAllowAnyAudience() {
        runner.run(context -> assertThat(context.getBean(OAuthProperties.class).googleClientIds()).isEmpty());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OAuthProperties.class)
    static class PropertiesConfig {}
}

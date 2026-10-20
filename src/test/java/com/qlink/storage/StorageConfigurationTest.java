package com.qlink.storage;

import com.qlink.features.storage.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.MultipartConfigElement;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class StorageConfigurationTest {
    @TempDir Path directory;
    @Configuration @EnableConfigurationProperties(StorageProperties.class)
    static class PropertiesConfig {}
    ApplicationContextRunner runner(String profile) {
        return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfig.class,StorageMultipartConfiguration.class)
            .withPropertyValues("spring.profiles.active="+profile,"spring.config.import=optional:classpath:/no-local-env.properties");
    }
    @Test void developmentAndProductionDefaults() {
        runner("dev").run(c -> { assertThat(c).hasNotFailed(); assertThat(c.getBean(StorageProperties.class).rootPath()).isEqualTo("./storage/dev"); });
        runner("prod").run(c -> { assertThat(c).hasNotFailed(); assertThat(c.getBean(StorageProperties.class).rootPath()).isEqualTo("/var/lib/qlink/storage"); });
    }
    @Test void productionOverridesAndMultipartLimitAreAligned() {
        runner("prod").withPropertyValues("STORAGE_ROOT="+directory,"STORAGE_MAX_FILE_SIZE=2MB").run(c -> {
            assertThat(c).hasNotFailed(); assertThat(c.getBean(StorageProperties.class).rootPath()).isEqualTo(directory.toString());
            var multipart=c.getBean(MultipartConfigElement.class);
            assertThat(multipart.getMaxFileSize()).isEqualTo(2*1024*1024);
            assertThat(multipart.getMaxRequestSize()).isEqualTo(2*1024*1024+64*1024);
            assertThat(multipart.getFileSizeThreshold()).isZero();
        });
    }
    @Test void testProfileRequiresItsOwnDirectory() {
        runner("test").run(c -> assertThat(c).hasFailed());
        runner("test").withPropertyValues("TEST_STORAGE_ROOT="+directory,"STORAGE_ROOT=/never/use/production").run(c -> {
            assertThat(c).hasNotFailed(); assertThat(c.getBean(StorageProperties.class).rootPath()).isEqualTo(directory.toString());
        });
    }
    @Test void invalidLimitsFailConfiguration() {
        runner("dev").withPropertyValues("app.storage.max-file-size=0B").run(c -> assertThat(c).hasFailed());
    }
}

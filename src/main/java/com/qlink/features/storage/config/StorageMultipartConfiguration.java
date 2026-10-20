package com.qlink.features.storage.config;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.*;
@Configuration
public class StorageMultipartConfiguration {
    @Bean MultipartConfigElement multipartConfigElement(StorageProperties properties) {
        long limit=properties.maxFileSize().toBytes();
        // Same per-file limit as the streaming provider, plus bounded multipart framing overhead.
        return new MultipartConfigElement("",limit,Math.addExact(limit,64*1024L),0);
    }
}

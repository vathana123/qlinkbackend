package com.qlink.security.oauth;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
@ConfigurationProperties("qlink.oauth")
public record OAuthProperties(List<String> googleClientIds, List<String> appleClientIds,
                              List<String> googleAndroidClientIds) {}

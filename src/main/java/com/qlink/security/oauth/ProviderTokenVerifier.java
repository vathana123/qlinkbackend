package com.qlink.security.oauth;
import com.qlink.features.auth.enums.AuthProvider;
public interface ProviderTokenVerifier {
    ProviderIdentity verify(AuthProvider provider, String token);
}

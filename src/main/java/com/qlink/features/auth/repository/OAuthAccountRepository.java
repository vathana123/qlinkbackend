package com.qlink.features.auth.repository;
import com.qlink.features.auth.entity.OAuthAccount;
import com.qlink.features.auth.enums.AuthProvider;
import org.springframework.data.repository.Repository;
import java.util.*;
public interface OAuthAccountRepository extends Repository<OAuthAccount, UUID> {
    Optional<OAuthAccount> findByProviderAndProviderSubject(AuthProvider provider, String subject);
    OAuthAccount save(OAuthAccount account);
}

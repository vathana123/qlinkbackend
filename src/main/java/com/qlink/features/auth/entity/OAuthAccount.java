package com.qlink.features.auth.entity;
import com.qlink.common.entity.BaseEntity;
import com.qlink.features.auth.enums.AuthProvider;
import com.qlink.features.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

@Entity @Table(name = "user_oauth_accounts")
@SQLRestriction("deleted_at IS NULL")
@Getter @Setter @NoArgsConstructor
public class OAuthAccount extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private AuthProvider provider;
    @Column(nullable = false, length = 255) private String providerSubject;
    @Column(length = 320) private String providerEmail;
}

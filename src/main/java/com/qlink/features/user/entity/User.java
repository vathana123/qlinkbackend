package com.qlink.features.user.entity;

import com.qlink.common.entity.BaseEntity;
import com.qlink.features.user.enums.UserStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

@Entity @Table(name = "users")
@SQLRestriction("deleted_at IS NULL")
@Getter @Setter @NoArgsConstructor
public class User extends BaseEntity {
    @Column(length = 255) private String displayName;
    @Column(length = 30) private String username;
    @Column(length = 320) private String email;
    // Self-reported discovery data. Never a verified identity or authentication credential.
    @Column(length = 16) private String phoneNumber;
    public void setPhoneNumber(String value) {
        if (value == null || value.isBlank()) { phoneNumber = null; return; }
        String normalized = com.qlink.features.user.service.SearchKeyword.normalizePhone(value);
        if (normalized == null) throw com.qlink.features.user.service.SearchKeyword.invalid("Use an international phone number including country code");
        phoneNumber = normalized;
    }
    @Column(length = 2048) private String avatarUrl;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;
}

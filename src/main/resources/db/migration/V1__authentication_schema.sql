CREATE TABLE users (
    id BINARY(16) PRIMARY KEY,
    display_name VARCHAR(255),
    email VARCHAR(320),
    avatar_url VARCHAR(2048),
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    created_by BINARY(16),
    updated_by BINARY(16),
    deleted_by BINARY(16)
);

CREATE TABLE user_oauth_accounts (
    id BINARY(16) PRIMARY KEY,
    user_id BINARY(16) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_subject VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_email VARCHAR(320),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    created_by BINARY(16),
    updated_by BINARY(16),
    deleted_by BINARY(16),
    active_identity TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) STORED,
    CONSTRAINT fk_oauth_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uq_active_provider_identity UNIQUE (provider, provider_subject, active_identity)
);

CREATE TABLE auth_sessions (
    id BINARY(16) PRIMARY KEY,
    user_id BINARY(16) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    created_by BINARY(16),
    updated_by BINARY(16),
    deleted_by BINARY(16),
    CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_session_user (user_id),
    INDEX idx_session_expiry (expires_at)
);

CREATE TABLE refresh_tokens (
    id BINARY(16) PRIMARY KEY,
    session_id BINARY(16) NOT NULL,
    token_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6),
    replaced_by BINARY(16),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    created_by BINARY(16),
    updated_by BINARY(16),
    deleted_by BINARY(16),
    CONSTRAINT fk_refresh_session FOREIGN KEY (session_id) REFERENCES auth_sessions(id),
    CONSTRAINT fk_refresh_replacement FOREIGN KEY (replaced_by) REFERENCES refresh_tokens(id),
    CONSTRAINT uq_refresh_hash UNIQUE (token_hash),
    INDEX idx_refresh_session (session_id)
);

CREATE TABLE nonce_challenges (
    id BINARY(16) PRIMARY KEY,
    nonce_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider VARCHAR(20) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    created_by BINARY(16),
    updated_by BINARY(16),
    deleted_by BINARY(16),
    CONSTRAINT uq_nonce_hash UNIQUE (nonce_hash),
    INDEX idx_nonce_expiry (expires_at)
);

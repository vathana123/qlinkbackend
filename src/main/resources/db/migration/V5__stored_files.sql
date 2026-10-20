CREATE TABLE stored_files (
    id BINARY(16) PRIMARY KEY,
    original_name VARCHAR(255) NOT NULL,
    storage_key VARCHAR(500) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_type VARCHAR(150) NOT NULL,
    size_bytes BIGINT NOT NULL,
    checksum_sha256 VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    category VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    uploaded_by BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    created_by BINARY(16),
    updated_by BINARY(16),
    deleted_by BINARY(16),
    CONSTRAINT uq_stored_file_key UNIQUE (storage_key),
    CONSTRAINT fk_stored_file_owner FOREIGN KEY (uploaded_by) REFERENCES users(id),
    CONSTRAINT ck_stored_file_size CHECK (size_bytes > 0),
    CONSTRAINT ck_stored_file_category CHECK (category IN ('IMAGE','AUDIO','VIDEO','DOCUMENT','OTHER')),
    CONSTRAINT ck_stored_file_status CHECK (status IN ('PENDING','AVAILABLE','DELETED')),
    CONSTRAINT ck_stored_file_deleted CHECK (
        (status = 'DELETED' AND deleted_at IS NOT NULL) OR (status <> 'DELETED' AND deleted_at IS NULL)
    ),
    INDEX idx_stored_file_owner (uploaded_by, deleted_at, created_at),
    INDEX idx_stored_file_cleanup (status, deleted_at)
);

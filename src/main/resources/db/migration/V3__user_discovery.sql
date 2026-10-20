ALTER TABLE users ADD COLUMN phone_number VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD INDEX idx_user_phone_number (phone_number),
    ADD CONSTRAINT ck_user_phone_number CHECK (phone_number IS NULL OR REGEXP_LIKE(phone_number, '^[+][1-9][0-9]{6,14}$'));

-- Shared limits across application instances; no search terms or contact details are recorded.
CREATE TABLE user_search_limits (
    user_id BINARY(16) PRIMARY KEY,
    minute_window BIGINT NOT NULL,
    minute_count INT NOT NULL,
    day_window BIGINT NOT NULL,
    day_count INT NOT NULL,
    CONSTRAINT fk_search_limit_user FOREIGN KEY (user_id) REFERENCES users(id)
);

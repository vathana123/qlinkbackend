ALTER TABLE chat_messages
 ADD COLUMN reply_to_id BINARY(16),
 ADD COLUMN edited_at DATETIME(6),
 ADD COLUMN removed_at DATETIME(6),
 ADD COLUMN removed_by BINARY(16),
 ADD COLUMN revision BIGINT NOT NULL DEFAULT 0,
 ADD CONSTRAINT fk_message_reply FOREIGN KEY (reply_to_id) REFERENCES chat_messages(id),
 DROP CHECK ck_message_payload,
 ADD CONSTRAINT ck_message_payload CHECK (
 (removed_at IS NOT NULL AND content = '') OR
 (removed_at IS NULL AND (
   (type = 'TEXT' AND CHAR_LENGTH(content) BETWEEN 1 AND 4000 AND file_id IS NULL AND duration_ms IS NULL)
   OR (type = 'VOICE' AND content = '' AND file_id IS NOT NULL AND duration_ms IS NOT NULL AND duration_ms BETWEEN 300 AND 120000
       AND audio_content_type IS NOT NULL AND audio_size_bytes IS NOT NULL AND audio_size_bytes BETWEEN 1 AND 4000000)
   OR (type IN ('IMAGE','FILE') AND content = '' AND file_id IS NOT NULL AND duration_ms IS NULL
       AND audio_content_type IS NULL AND audio_size_bytes IS NULL)
 )));
CREATE TABLE message_hidden (
 id BINARY(16) PRIMARY KEY, message_id BINARY(16) NOT NULL, user_id BINARY(16) NOT NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
 created_by BINARY(16), updated_by BINARY(16), deleted_by BINARY(16),
 CONSTRAINT uq_hidden_message UNIQUE(message_id,user_id),
 CONSTRAINT fk_hidden_message FOREIGN KEY(message_id) REFERENCES chat_messages(id),
 CONSTRAINT fk_hidden_user FOREIGN KEY(user_id) REFERENCES users(id)
);

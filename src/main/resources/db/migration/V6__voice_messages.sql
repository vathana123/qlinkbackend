ALTER TABLE chat_messages
 DROP CHECK ck_message_type,
 DROP CHECK ck_message_length,
 ADD COLUMN file_id BINARY(16),
 ADD COLUMN duration_ms BIGINT,
 ADD COLUMN audio_content_type VARCHAR(255),
 ADD COLUMN audio_size_bytes BIGINT,
 ADD CONSTRAINT fk_message_file FOREIGN KEY (file_id) REFERENCES stored_files(id),
 ADD CONSTRAINT uq_message_file UNIQUE (file_id),
 ADD CONSTRAINT ck_message_payload CHECK (
   (type = 'TEXT' AND CHAR_LENGTH(content) BETWEEN 1 AND 4000 AND file_id IS NULL AND duration_ms IS NULL)
   OR (type = 'VOICE' AND content = '' AND file_id IS NOT NULL AND duration_ms IS NOT NULL AND duration_ms BETWEEN 300 AND 120000
       AND audio_content_type IS NOT NULL AND audio_size_bytes IS NOT NULL AND audio_size_bytes BETWEEN 1 AND 4000000)
 );

ALTER TABLE stored_files ADD COLUMN voice_recording BOOLEAN NOT NULL DEFAULT FALSE;

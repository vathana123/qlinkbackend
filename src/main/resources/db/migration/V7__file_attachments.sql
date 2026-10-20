-- Reuse V6 file_id, its foreign key and unique constraint, and stored_files metadata.
ALTER TABLE chat_messages
 DROP CHECK ck_message_payload,
 ADD CONSTRAINT ck_message_payload CHECK (
   (type = 'TEXT' AND CHAR_LENGTH(content) BETWEEN 1 AND 4000 AND file_id IS NULL AND duration_ms IS NULL)
   OR (type = 'VOICE' AND content = '' AND file_id IS NOT NULL AND duration_ms IS NOT NULL AND duration_ms BETWEEN 300 AND 120000
       AND audio_content_type IS NOT NULL AND audio_size_bytes IS NOT NULL AND audio_size_bytes BETWEEN 1 AND 4000000)
   OR (type IN ('IMAGE','FILE') AND content = '' AND file_id IS NOT NULL AND duration_ms IS NULL
       AND audio_content_type IS NULL AND audio_size_bytes IS NULL)
 );

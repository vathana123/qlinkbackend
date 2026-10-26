CREATE TABLE chat_event_jobs (
 id BINARY(16) PRIMARY KEY, message_id BINARY(16) NOT NULL, event_type VARCHAR(30) NOT NULL, only_user BINARY(16),
 attempts INT NOT NULL DEFAULT 0, next_attempt_at DATETIME(6) NOT NULL, finished_at DATETIME(6),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
 created_by BINARY(16), updated_by BINARY(16), deleted_by BINARY(16),
 CONSTRAINT fk_event_message FOREIGN KEY(message_id) REFERENCES chat_messages(id),
 INDEX idx_event_due(finished_at,next_attempt_at)
);

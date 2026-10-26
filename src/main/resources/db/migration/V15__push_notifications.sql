CREATE TABLE push_devices (
 id BINARY(16) PRIMARY KEY, installation_id BINARY(16) NOT NULL, user_id BINARY(16) NOT NULL, session_id BINARY(16) NOT NULL,
 token TEXT NOT NULL, token_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, platform VARCHAR(10) NOT NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
 created_by BINARY(16), updated_by BINARY(16), deleted_by BINARY(16),
 UNIQUE KEY uq_push_installation(installation_id), UNIQUE KEY uq_push_token(token_hash),
 CONSTRAINT fk_push_user FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT fk_push_session FOREIGN KEY(session_id) REFERENCES auth_sessions(id), INDEX idx_push_user(user_id)
);
CREATE TABLE room_notification_preferences (
 id BINARY(16) PRIMARY KEY, room_id BINARY(16) NOT NULL, user_id BINARY(16) NOT NULL, muted BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
 created_by BINARY(16), updated_by BINARY(16), deleted_by BINARY(16),
 UNIQUE KEY uq_room_notification(room_id,user_id),
 CONSTRAINT fk_notification_room FOREIGN KEY(room_id) REFERENCES chat_rooms(id),
 CONSTRAINT fk_notification_user FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE push_deliveries (
 id BINARY(16) PRIMARY KEY, message_id BINARY(16) NOT NULL, device_id BINARY(16) NOT NULL, recipient_id BINARY(16) NOT NULL,
 attempts INT NOT NULL DEFAULT 0, next_attempt_at DATETIME(6) NOT NULL, finished_at DATETIME(6),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
 created_by BINARY(16), updated_by BINARY(16), deleted_by BINARY(16),
 UNIQUE KEY uq_push_message_device(message_id,device_id), INDEX idx_push_due(finished_at,next_attempt_at),
 CONSTRAINT fk_push_message FOREIGN KEY(message_id) REFERENCES chat_messages(id)
);

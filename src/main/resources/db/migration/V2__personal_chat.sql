ALTER TABLE users ADD COLUMN username VARCHAR(30) CHARACTER SET ascii COLLATE ascii_general_ci NULL,
    ADD CONSTRAINT uq_user_username UNIQUE (username);

CREATE TABLE chat_rooms (
 id BINARY(16) PRIMARY KEY,
 type VARCHAR(20) NOT NULL,
 creator_id BINARY(16) NOT NULL,
 personal_pair_key VARCHAR(73) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 last_message_at DATETIME(6),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
 created_by BINARY(16), updated_by BINARY(16), deleted_by BINARY(16),
 active_pair VARCHAR(73) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (IF(deleted_at IS NULL, personal_pair_key, NULL)) STORED,
 CONSTRAINT uq_active_personal_pair UNIQUE (active_pair),
 CONSTRAINT fk_room_creator FOREIGN KEY (creator_id) REFERENCES users(id),
 CONSTRAINT ck_personal_type CHECK (type = 'PERSONAL'),
 INDEX idx_rooms_recent (last_message_at, id)
);
CREATE TABLE chat_room_members (
 id BINARY(16) PRIMARY KEY, room_id BINARY(16) NOT NULL, user_id BINARY(16) NOT NULL,
 joined_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
 created_by BINARY(16), updated_by BINARY(16), deleted_by BINARY(16),
 active_member TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) STORED,
 CONSTRAINT uq_active_membership UNIQUE (room_id,user_id,active_member),
 CONSTRAINT fk_member_room FOREIGN KEY (room_id) REFERENCES chat_rooms(id),
 CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES users(id),
 INDEX idx_member_user (user_id, deleted_at, room_id)
);
CREATE TABLE chat_messages (
 id BINARY(16) PRIMARY KEY, room_id BINARY(16) NOT NULL, sender_id BINARY(16) NOT NULL,
 client_message_id BINARY(16) NOT NULL, type VARCHAR(20) NOT NULL, content TEXT NOT NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
 created_by BINARY(16), updated_by BINARY(16), deleted_by BINARY(16),
 CONSTRAINT uq_sender_client_message UNIQUE (sender_id,client_message_id),
 CONSTRAINT fk_message_room FOREIGN KEY (room_id) REFERENCES chat_rooms(id),
 CONSTRAINT fk_message_sender FOREIGN KEY (sender_id) REFERENCES users(id),
 CONSTRAINT ck_message_type CHECK (type = 'TEXT'),
 CONSTRAINT ck_message_length CHECK (CHAR_LENGTH(content) BETWEEN 1 AND 4000),
 INDEX idx_message_cursor (room_id,deleted_at,created_at,id)
);

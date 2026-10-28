-- Keep an index for the file foreign key before removing single-message ownership.
CREATE INDEX idx_message_file_access ON chat_messages(file_id, deleted_at, removed_at);
ALTER TABLE chat_messages DROP INDEX uq_message_file;
ALTER TABLE chat_messages
 ADD COLUMN forwarded_from_id BINARY(16),
 ADD CONSTRAINT fk_message_forward FOREIGN KEY (forwarded_from_id) REFERENCES chat_messages(id);

CREATE TABLE message_reactions (
 message_id BINARY(16) NOT NULL,
 user_id BINARY(16) NOT NULL,
 emoji VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 PRIMARY KEY (message_id, user_id, emoji),
 CONSTRAINT fk_reaction_message FOREIGN KEY (message_id) REFERENCES chat_messages(id),
 CONSTRAINT fk_reaction_user FOREIGN KEY (user_id) REFERENCES users(id)
);

ALTER TABLE users
 ADD COLUMN online_visibility VARCHAR(16) NOT NULL DEFAULT 'CONTACTS',
 ADD COLUMN last_seen_visibility VARCHAR(16) NOT NULL DEFAULT 'CONTACTS';
CREATE TABLE user_blocks (
 id BINARY(16) NOT NULL PRIMARY KEY,
 blocker_id BINARY(16) NOT NULL,
 blocked_id BINARY(16) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT uq_user_block UNIQUE (blocker_id, blocked_id),
 INDEX idx_blocked_user (blocked_id, blocker_id),
 CONSTRAINT fk_block_actor FOREIGN KEY (blocker_id) REFERENCES users(id),
 CONSTRAINT fk_block_target FOREIGN KEY (blocked_id) REFERENCES users(id),
 CONSTRAINT ck_block_not_self CHECK (blocker_id <> blocked_id)
);

ALTER TABLE chat_room_members
    ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX ix_member_chat_management
    ON chat_room_members (user_id, deleted_at, archived, pinned);

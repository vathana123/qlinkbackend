ALTER TABLE chat_rooms
 ADD COLUMN description VARCHAR(2000),
 ADD COLUMN avatar_file_id BINARY(16),
 ADD COLUMN members_can_send BOOLEAN NOT NULL DEFAULT TRUE,
 ADD COLUMN members_can_invite BOOLEAN NOT NULL DEFAULT FALSE,
 ADD CONSTRAINT fk_group_avatar FOREIGN KEY (avatar_file_id) REFERENCES stored_files(id),
 ADD CONSTRAINT uq_group_avatar UNIQUE (avatar_file_id);

ALTER TABLE chat_room_members
 DROP CHECK ck_chat_room_member_role,
 ADD CONSTRAINT ck_chat_room_member_role CHECK (role IN ('OWNER','CO_OWNER','MEMBER')),
 ADD COLUMN active_owner BINARY(16) GENERATED ALWAYS AS
   (IF(deleted_at IS NULL AND role = 'OWNER', room_id, NULL)) STORED,
 ADD CONSTRAINT uq_group_active_owner UNIQUE (active_owner);
-- The service locks the room for every mutation and forbids removing/leaving
-- the owner. Transfer demotes then promotes within one atomic transaction.

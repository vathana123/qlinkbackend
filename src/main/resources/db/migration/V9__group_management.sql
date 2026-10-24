ALTER TABLE chat_rooms
 ADD COLUMN description VARCHAR(2000),
 ADD COLUMN avatar_file_id BINARY(16),
 ADD COLUMN members_can_send BOOLEAN NOT NULL DEFAULT TRUE,
 ADD COLUMN members_can_invite BOOLEAN NOT NULL DEFAULT FALSE,
 ADD CONSTRAINT fk_group_avatar FOREIGN KEY (avatar_file_id) REFERENCES stored_files(id),
 ADD CONSTRAINT uq_group_avatar UNIQUE (avatar_file_id);

-- Preserve an existing owner when present, otherwise promote the group creator;
-- the oldest active member is a final fallback for older inconsistent records.
CREATE TEMPORARY TABLE qlink_group_owner_backfill AS
SELECT r.id AS room_id,
       COALESCE(
         MAX(CASE WHEN m.role = 'OWNER' AND m.user_id = r.creator_id THEN m.id END),
         MIN(CASE WHEN m.role = 'OWNER' THEN m.id END),
         MAX(CASE WHEN m.user_id = r.creator_id THEN m.id END),
         MIN(m.id)
       ) AS member_id
FROM chat_room_members m
JOIN chat_rooms r ON r.id = m.room_id
WHERE r.deleted_at IS NULL
  AND r.type IN ('GROUP_PRIVATE','GROUP_PUBLIC')
  AND m.deleted_at IS NULL
GROUP BY r.id;

UPDATE chat_room_members m
JOIN qlink_group_owner_backfill backfill ON backfill.room_id = m.room_id
JOIN chat_rooms r ON r.id = m.room_id
SET m.role = IF(m.id = backfill.member_id, 'OWNER', 'MEMBER')
WHERE r.deleted_at IS NULL
  AND r.type IN ('GROUP_PRIVATE','GROUP_PUBLIC')
  AND m.deleted_at IS NULL;
DROP TEMPORARY TABLE qlink_group_owner_backfill;

ALTER TABLE chat_room_members
 DROP CHECK ck_chat_room_member_role,
 ADD CONSTRAINT ck_chat_room_member_role CHECK (role IN ('OWNER','CO_OWNER','MEMBER')),
 ADD COLUMN active_owner BINARY(16) GENERATED ALWAYS AS
   (IF(deleted_at IS NULL AND role = 'OWNER', room_id, NULL)) STORED,
 ADD CONSTRAINT uq_group_active_owner UNIQUE (active_owner);
-- The service locks the room for every mutation and forbids removing/leaving
-- the owner. Transfer demotes then promotes within one atomic transaction.

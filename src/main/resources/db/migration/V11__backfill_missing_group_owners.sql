-- V9 is immutable: its original applied checksum is -2128036722.
-- Fill only missing owners; never demote existing owners or co-owners.
-- Prefer the creator, then the earliest joined active member (UUID breaks ties).
CREATE TEMPORARY TABLE qlink_group_owner_backfill AS
SELECT room_id, member_id
FROM (
 SELECT r.id AS room_id, m.id AS member_id,
        ROW_NUMBER() OVER (
          PARTITION BY r.id
          ORDER BY (m.user_id = r.creator_id) DESC, m.joined_at, m.id
        ) AS candidate_rank
 FROM chat_rooms r
 JOIN chat_room_members m ON m.room_id = r.id AND m.deleted_at IS NULL
 WHERE r.deleted_at IS NULL
   AND r.type IN ('GROUP_PRIVATE','GROUP_PUBLIC')
   AND NOT EXISTS (
     SELECT 1 FROM chat_room_members owner
     WHERE owner.room_id = r.id AND owner.deleted_at IS NULL AND owner.role = 'OWNER'
   )
) candidates
WHERE candidate_rank = 1;

UPDATE chat_room_members m
JOIN qlink_group_owner_backfill backfill ON backfill.member_id = m.id
SET m.role = 'OWNER';
DROP TEMPORARY TABLE qlink_group_owner_backfill;

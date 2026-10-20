-- Defaults preserve all existing active and soft-deleted personal data.
-- One ALTER keeps the personal-pair unique constraint present across the schema change.
ALTER TABLE chat_rooms
    DROP CHECK ck_personal_type,
    DROP INDEX uq_active_personal_pair,
    DROP COLUMN active_pair,
    MODIFY COLUMN personal_pair_key VARCHAR(73) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN message_history_visibility VARCHAR(30) NOT NULL DEFAULT 'VIEW_ALL_MESSAGES',
    ADD COLUMN group_name VARCHAR(255) NULL,
    ADD COLUMN active_pair VARCHAR(73) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (IF(deleted_at IS NULL AND type = 'PERSONAL', personal_pair_key, NULL)) STORED,
    ADD CONSTRAINT uq_active_personal_pair UNIQUE (active_pair),
    ADD CONSTRAINT ck_chat_room_type CHECK (type IN ('PERSONAL', 'GROUP_PRIVATE', 'GROUP_PUBLIC')),
    ADD CONSTRAINT ck_chat_room_pair CHECK (
        (type = 'PERSONAL' AND personal_pair_key IS NOT NULL) OR
        (type IN ('GROUP_PRIVATE', 'GROUP_PUBLIC') AND personal_pair_key IS NULL)
    ),
    ADD CONSTRAINT ck_message_history_visibility CHECK (
        message_history_visibility IN ('VIEW_ALL_MESSAGES', 'VIEW_AFTER_JOIN')
    );

-- Personal room creators remain recorded on chat_rooms; no group ownership is inferred.
ALTER TABLE chat_room_members
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    ADD CONSTRAINT ck_chat_room_member_role CHECK (role IN ('OWNER', 'MEMBER'));

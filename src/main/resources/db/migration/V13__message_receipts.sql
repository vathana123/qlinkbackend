ALTER TABLE chat_messages
 ADD COLUMN recipient_count INT NOT NULL DEFAULT 0,
 ADD COLUMN delivered_count INT NOT NULL DEFAULT 0,
 ADD COLUMN seen_count INT NOT NULL DEFAULT 0;
CREATE TABLE message_receipts (
 id BINARY(16) PRIMARY KEY, message_id BINARY(16) NOT NULL, user_id BINARY(16) NOT NULL, membership_id BINARY(16) NOT NULL,
 delivered_at DATETIME(6), seen_at DATETIME(6),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
 created_by BINARY(16), updated_by BINARY(16), deleted_by BINARY(16),
 CONSTRAINT uq_message_recipient UNIQUE(message_id,user_id),
 CONSTRAINT fk_receipt_message FOREIGN KEY(message_id) REFERENCES chat_messages(id),
 CONSTRAINT fk_receipt_user FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT fk_receipt_membership FOREIGN KEY(membership_id) REFERENCES chat_room_members(id),
 INDEX idx_receipt_user(user_id,message_id)
);
INSERT INTO message_receipts(id,message_id,user_id,membership_id,created_at,updated_at)
SELECT UUID_TO_BIN(UUID()),m.id,member.user_id,member.id,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
FROM chat_messages m JOIN chat_rooms r ON r.id=m.room_id
JOIN chat_room_members member ON member.room_id=r.id AND member.deleted_at IS NULL AND member.user_id<>m.sender_id
JOIN users u ON u.id=member.user_id AND u.status='ACTIVE' AND u.deleted_at IS NULL
WHERE m.deleted_at IS NULL AND r.deleted_at IS NULL
AND (r.type='PERSONAL' OR r.message_history_visibility='VIEW_ALL_MESSAGES' OR m.created_at>=member.joined_at);
UPDATE chat_messages m SET recipient_count=(SELECT COUNT(*) FROM message_receipts receipt WHERE receipt.message_id=m.id);

-- Literal substring matching supports Khmer, short words and punctuation. MySQL
-- FULLTEXT's token/stopword rules would omit valid matches. Bound the candidate
-- scan using active memberships, text/deletion predicates and keyset ordering.
CREATE INDEX idx_message_search_room ON chat_messages
    (room_id, type, deleted_at, removed_at, created_at, id);
CREATE INDEX idx_message_search_global ON chat_messages
    (type, deleted_at, removed_at, created_at, id);
CREATE INDEX idx_message_search_sender ON chat_messages
    (sender_id, type, deleted_at, removed_at, created_at, id);
CREATE INDEX idx_room_search_cursor ON chat_rooms (deleted_at, created_at, id);

ALTER TABLE users ADD COLUMN avatar_file_id BINARY(16) NULL,
 ADD CONSTRAINT fk_user_avatar FOREIGN KEY (avatar_file_id) REFERENCES stored_files(id);

ALTER TABLE stored_files
 ADD COLUMN upload_key BINARY(16),
 ADD CONSTRAINT uq_upload_owner_key UNIQUE(uploaded_by,upload_key);

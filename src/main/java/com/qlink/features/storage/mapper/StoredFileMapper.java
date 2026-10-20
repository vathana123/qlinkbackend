package com.qlink.features.storage.mapper;
import com.qlink.features.storage.dto.StoredFileResponse;
import com.qlink.features.storage.entity.StoredFile;
import org.mapstruct.Mapper;
@Mapper(componentModel="spring")
public interface StoredFileMapper { StoredFileResponse toResponse(StoredFile file); }

package com.qlink.features.user.mapper;
import com.qlink.features.user.dto.UserResponse;
import com.qlink.features.user.entity.User;
import org.mapstruct.Mapper;
@Mapper(componentModel = "spring")
public interface UserMapper { UserResponse toResponse(User user);
    com.qlink.features.user.dto.PublicUserResponse toPublic(User user); }

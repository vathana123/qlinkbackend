package com.qlink.features.user.service;
import com.qlink.common.exception.ApiException;
import com.qlink.features.user.entity.User;
import com.qlink.features.user.enums.UserStatus;
import com.qlink.features.user.repository.UserRepository;
import com.qlink.features.user.mapper.UserMapper;
import com.qlink.features.user.dto.UserResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class UserService {
    private final UserRepository users;
    private final UserMapper mapper;
    public UserService(UserRepository users, UserMapper mapper) { this.users = users; this.mapper = mapper; }
    public User requireActive(UUID id) {
        var user = users.findById(id).orElseThrow(() -> ApiException.unauthorized("USER_UNAVAILABLE", "User is unavailable"));
        if (user.getStatus() != UserStatus.ACTIVE) throw ApiException.unauthorized("USER_UNAVAILABLE", "User is unavailable");
        return user;
    }
    @Transactional(readOnly = true)
    public UserResponse me(UUID id) { return mapper.toResponse(requireActive(id)); }
    @Transactional
    public void softDelete(UUID id, UUID actor) { requireActive(id).softDelete(actor); }
}

package com.qlink.features.user.service;
import lombok.RequiredArgsConstructor;
import com.qlink.common.exception.ApiException;
import com.qlink.features.user.dto.*;
import com.qlink.features.user.entity.User;
import com.qlink.features.user.enums.UserStatus;
import com.qlink.features.user.mapper.UserMapper;
import com.qlink.features.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;
import java.util.*;
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DiscoveryService {
    private final UserRepository users; private final UserService current; private final UserMapper mapper;
    private final SearchRateLimiter searchLimits;

    public static String normalize(String name) {
        if (name == null) throw invalid("Username is required");
        String value = name.toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9_]{3,30}") || Set.of("admin", "system", "support").contains(value))
            throw invalid("Use 3–30 letters, numbers or underscores and a non-reserved name");
        return value;
    }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
    public User recipient(UUID viewer, UUID id) {
        current.requireActive(viewer);
        if (viewer.equals(id)) throw invalid("You cannot start a conversation with yourself");
        return users.findById(id).filter(u -> u.getStatus() == UserStatus.ACTIVE)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User is unavailable"));
    }
    public PublicUserResponse resolve(UUID viewer, UUID id) { return mapper.toPublic(recipient(viewer,id)); }
    public Page<PublicUserResponse> search(UUID viewer, String query, int page, int size) {
        current.requireActive(viewer);
        searchLimits.acquire(viewer);
        if (page < 0 || size < 1 || size > 50 || (long) page * size >= 200) throw invalid("Invalid pagination; search is limited to the first 200 results");
        var keyword = SearchKeyword.parse(query);
        // Bound the discoverable result window without changing the Page response shape.
        var found = users.search(viewer, keyword.pattern(), keyword.email(), keyword.phone(), PageRequest.of(page,size));
        int available = Math.min(size, 200 - page * size);
        var content = found.getContent().stream().limit(available).map(mapper::toPublic).toList();
        return new PageImpl<>(content, PageRequest.of(page,size), Math.min(200, found.getTotalElements()));
    }
    @Transactional
    public void updatePhone(UUID viewer, String phoneNumber) {
        User user = current.requireActive(viewer);
        user.setPhoneNumber(phoneNumber);
        users.saveAndFlush(user);
    }

    @Transactional
    public UserResponse update(UUID viewer, String name) {
        User user = current.requireActive(viewer); user.setUsername(normalize(name));
        try { users.saveAndFlush(user); }
        catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "This username is already taken");
        }
        return mapper.toResponse(user);
    }
}

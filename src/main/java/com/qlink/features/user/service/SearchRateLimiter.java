package com.qlink.features.user.service;

import com.qlink.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class SearchRateLimiter {
    private final JdbcTemplate jdbc;
    public SearchRateLimiter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Independent transaction: a failed/invalid search still consumes its allowance.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void acquire(UUID viewer) {
        long now = jdbc.queryForObject("select UNIX_TIMESTAMP()", Long.class);
        long minute = now / 60, day = now / 86400;
        String id = viewer.toString();
        jdbc.update("""
            insert into user_search_limits (user_id, minute_window, minute_count, day_window, day_count)
            values (UUID_TO_BIN(?), ?, 0, ?, 0)
            on duplicate key update user_id = user_id
            """, id, minute, day);
        var counts = jdbc.queryForObject("""
            select minute_window, minute_count, day_window, day_count
            from user_search_limits where user_id = UUID_TO_BIN(?) for update
            """, (rs, row) -> new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4)}, id);
        long minuteCount = counts[0] == minute ? counts[1] : 0;
        long dayCount = counts[2] == day ? counts[3] : 0;
        if (minuteCount >= 30 || dayCount >= 200)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SEARCH_RATE_LIMITED", "Search limit reached. Please try again later.");
        jdbc.update("""
            update user_search_limits set minute_window=?, minute_count=?, day_window=?, day_count=?
            where user_id=UUID_TO_BIN(?)
            """, minute, minuteCount + 1, day, dayCount + 1, id);
    }
}

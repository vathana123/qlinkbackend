package com.qlink.features.user.service;

import com.qlink.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import java.util.Locale;

/** Only public names use partial matching; contact fields always use exact matching. */
public record SearchKeyword(String pattern, String email, String phone) {
    public static SearchKeyword parse(String raw) {
        String term = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (term.length() < 3 || term.length() > 254 || term.chars().anyMatch(Character::isISOControl))
            throw invalid("Search using 3–254 characters");
        String email = term.matches("[^\\s@]+@[^\\s@]+[.][^\\s@]+") ? term : null;
        String pattern = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return new SearchKeyword(pattern, email, normalizePhone(term));
    }

    /** No region is guessed. Only explicit + or 00 international numbers are canonicalized. */
    public static String normalizePhone(String raw) {
        if (raw == null || raw.length() > 64 || !raw.strip().matches("[+0-9(). -]+")) return null;
        String value = raw.strip().replaceAll("[(). -]", "");
        if (value.startsWith("00")) value = "+" + value.substring(2);
        return value.matches("[+][1-9][0-9]{6,14}") ? value : null;
    }
    public static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}

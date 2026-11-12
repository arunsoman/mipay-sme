package com.mipay.sme.service;

import com.mipay.sme.common.BadRequestException;
import com.mipay.sme.common.ConflictException;
import com.mipay.sme.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Username rules [H6]: 3-32 chars, pattern [a-z][a-z0-9._-]+, must contain a
 * letter (guaranteed by the leading lowercase letter) and must not look like
 * a phone number. Uniqueness is checked against usernames AND phones so a
 * username can never shadow some other user's login identifier.
 */
@Component
@RequiredArgsConstructor
public class UsernamePolicy {

    private static final Pattern FORMAT = Pattern.compile("^[a-z][a-z0-9._-]{2,31}$");
    private static final Pattern LOOKS_LIKE_PHONE = Pattern.compile("^\\+?[0-9][0-9 '.-]{6,}$");

    private final UserRepository userRepo;
    private final SecureRandom random = new SecureRandom();

    /**
     * Usernames are created by the system, never typed by the onboarder: the
     * local part of the email (or first.last from the name), folded to
     * [a-z0-9._-], starting with a letter, 3-24 chars, made unique against
     * usernames AND phones by a numeric suffix. The result always passes
     * {@link #validate(String)}; it reaches the user with the activation link.
     */
    public String generate(String fullName, String email) {
        String base = slug(email != null && email.contains("@") ? email.substring(0, email.indexOf('@')) : null, true);
        if (base == null) base = slug(fullName, false);
        if (base == null) base = "user";
        if (!Character.isLetter(base.charAt(0))) base = "u" + base;
        if (base.length() > 24) base = base.substring(0, 24).replaceAll("[._-]+$", "");
        while (base.length() < 3) base = base + "x";

        if (free(base)) return base;
        for (int i = 0; i < 30; i++) {
            String candidate = base + (i < 10 ? String.valueOf(2 + i) : String.valueOf(100 + random.nextInt(9900)));
            if (free(candidate)) return candidate;
        }
        return base.substring(0, Math.min(base.length(), 20)) + "." + Integer.toString(random.nextInt(1 << 20), 36);
    }

    /**
     * An onboarded org's administrator: the legal business name folded to [a-z0-9] followed by
     * the MCC, e.g. "Namib Fresh (Pty) Ltd" + 5411 → "namibfreshptyltd5411"; "-2", "-3"… when
     * taken. Falls back to {@link #generate} without a usable name.
     */
    public String forBusiness(String legalName, String mcc, String fallbackName, String fallbackEmail) {
        String name = legalName == null ? "" : Normalizer.normalize(legalName.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String code = mcc == null ? "" : mcc.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
        if (name.isEmpty()) return generate(fallbackName, fallbackEmail);
        if (!Character.isLetter(name.charAt(0))) name = "o" + name;
        int room = 28 - code.length();                     // keeps "-NN" within the 32-char limit
        if (name.length() > room) name = name.substring(0, Math.max(3, room));
        String base = name + code;
        if (free(base)) return base;
        for (int i = 2; i < 100; i++) {
            if (free(base + "-" + i)) return base + "-" + i;
        }
        return generate(fallbackName, fallbackEmail);
    }

    private boolean free(String candidate) {
        return FORMAT.matcher(candidate).matches() && !LOOKS_LIKE_PHONE.matcher(candidate).matches()
                && !userRepo.existsByUsername(candidate) && !userRepo.existsByPhone(candidate);
    }

    /** "Selma Ndapewa Nghipandulwa" → "selma.nghipandulwa"; "S.Nghi+ops" → "s.nghi.ops". */
    private static String slug(String raw, boolean keepAllParts) {
        if (raw == null || raw.isBlank()) return null;
        String ascii = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        String[] words = ascii.split("[^a-z0-9]+");
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String w : words) if (!w.isBlank()) parts.add(w);
        if (parts.isEmpty()) return null;
        String joined = keepAllParts || parts.size() <= 2 ? String.join(".", parts)
                : parts.get(0) + "." + parts.get(parts.size() - 1);   // first.last for long names
        return joined.isBlank() ? null : joined;
    }

    public void validate(String username) {
        if (username == null || username.isBlank()) {
            return; // optional field
        }
        if (!FORMAT.matcher(username).matches()) {
            throw new BadRequestException("USERNAME_INVALID",
                    "Username must be 3-32 chars matching [a-z][a-z0-9._-]");
        }
        if (LOOKS_LIKE_PHONE.matcher(username).matches()) {
            throw new BadRequestException("USERNAME_INVALID",
                    "Username must not look like a phone number (H6)");
        }
        if (userRepo.existsByUsername(username) || userRepo.existsByPhone(username)) {
            throw new ConflictException("USERNAME_TAKEN", "Username already in use");
        }
    }
}

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
     * An onboarded org's administrator login (kyc-filed.md, "Administrator login name"):
     * <ol>
     *   <li>the Legal Business Name folded to [a-z0-9], e.g. "Namib Fresh (Pty) Ltd" → "namibfreshptyltd";</li>
     *   <li>when another business already has that name, the name followed by its Business
     *       Registration / Corporate ID Number, e.g. "CC/2019/04512" → "namibfreshptyltd-cc201904512";</li>
     *   <li>should even that be taken (or no registration number given), "-2", "-3"… on step 2's name.</li>
     * </ol>
     * Everything stays within the 32-char limit. Falls back to {@link #generate} without a usable name.
     */
    public String forBusiness(String legalName, String registrationNumber, String fallbackName, String fallbackEmail) {
        String name = fold(legalName);
        if (name.isEmpty()) return generate(fallbackName, fallbackEmail);
        if (!Character.isLetter(name.charAt(0))) name = "o" + name;
        while (name.length() < 3) name = name + "x";
        if (name.length() > 32) name = name.substring(0, 32);
        if (free(name)) return name;

        String reg = fold(registrationNumber);
        if (reg.length() > 24) reg = reg.substring(reg.length() - 24);          // the distinctive tail
        String base = name;
        if (!reg.isEmpty()) {
            int room = 32 - reg.length() - 1;                                    // name + "-" + reg
            base = name.substring(0, Math.min(name.length(), Math.max(3, room))) + "-" + reg;
            if (base.length() > 32) base = base.substring(0, 32);
            if (free(base)) return base;
        }
        String stem = base.length() > 28 ? base.substring(0, 28) : base;      // keeps "-NN" within 32
        for (int i = 2; i < 100; i++) {
            if (free(stem + "-" + i)) return stem + "-" + i;
        }
        return generate(fallbackName, fallbackEmail);
    }

    /** Accents dropped, lower case, only [a-z0-9] kept. */
    private static String fold(String raw) {
        return raw == null ? "" : Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
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

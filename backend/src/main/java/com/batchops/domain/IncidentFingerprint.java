package com.batchops.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Stable identity for "the same failure". A flapping upload updates one incident's occurrence
 * count instead of flooding the Issues page with a new incident and ticket per attempt.
 */
public final class IncidentFingerprint {

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private IncidentFingerprint() {
    }

    /** Error text with volatile parts (ids, counts, row numbers) removed. */
    public static String errorClass(String error) {
        String t = error == null ? "" : error.toLowerCase(Locale.ROOT);
        t = UUID.matcher(t).replaceAll("<id>");
        t = DIGITS.matcher(t).replaceAll("#");
        t = SPACES.matcher(t).replaceAll(" ").trim();
        return t.length() > 160 ? t.substring(0, 160) : t;
    }

    public static String of(String uploadId, String step, String error) {
        String raw = uploadId + "|" + step + "|" + errorClass(error);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

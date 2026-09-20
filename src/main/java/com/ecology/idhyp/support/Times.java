package com.ecology.idhyp.support;

import java.time.Instant;
import java.time.OffsetDateTime;

/** ISO-8601 parsing with explicit offsets; instants are compared by epoch second. */
public final class Times {
    private Times() {
    }

    public static long epoch(String iso) {
        if (iso == null || iso.isBlank()) {
            throw new IllegalArgumentException("timestamp must be an ISO-8601 value with offset");
        }
        return OffsetDateTime.parse(iso).toInstant().getEpochSecond();
    }

    public static String nowIso() {
        return Instant.now().toString();
    }

    public static String normalize(String iso) {
        return OffsetDateTime.parse(iso).toInstant().toString();
    }
}

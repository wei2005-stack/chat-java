package com.chat.util;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class Times {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private Times() {
    }

    public static String iso(Timestamp timestamp) {
        return timestamp == null ? null : ISO.format(timestamp.toInstant());
    }
}

/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.common.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;

/**
 * A utility class to format things as a {@link String} with ease.
 */
public final class TextFormatter {

    private TextFormatter() {}

    /**
     * Appends the human-readable representation of the specified byte-unit {@code size} to the specified
     * {@link StringBuffer}.
     */
    public static void appendSize(StringBuilder buf, long size) {
        if (size >= 104857600) { // >= 100 MiB
            buf.append(size / 1048576).append("MiB(").append(size).append("B)");
        } else if (size >= 102400) { // >= 100 KiB
            buf.append(size / 1024).append("KiB(").append(size).append("B)");
        } else {
            buf.append(size).append('B');
        }
    }

    /**
     * Appends the human-readable representation of the duration given as {@code elapsed} to the specified
     * {@link StringBuilder}.
     */
    public static void appendElapsed(StringBuilder buf, long elapsedNanos) {
        if (elapsedNanos >= 100000000000L) { // >= 100 s
            buf.append(elapsedNanos / 1000000000L).append("s(").append(elapsedNanos).append("ns)");
        } else if (elapsedNanos >= 100000000L) { // >= 100 ms
            buf.append(elapsedNanos / 1000000L).append("ms(").append(elapsedNanos).append("ns)");
        } else if (elapsedNanos >= 100000L) { // >= 100 us
            buf.append(elapsedNanos / 1000L).append("\u00B5s(").append(elapsedNanos).append("ns)"); // microsecs
        } else {
            buf.append(elapsedNanos).append("ns");
        }
    }

    /**
     * Appends the human-readable representation of the duration between the specified {@code startTimeNanos}
     * and {@code endTimeNanos} to the specified {@link StringBuilder}.
     */
    public static void appendElapsed(StringBuilder buf, long startTimeNanos, long endTimeNanos) {
        appendElapsed(buf, endTimeNanos - startTimeNanos);
    }

    /**
     * A shortcut method that calls {@link #appendElapsed(StringBuilder, long, long)} and
     * {@link #appendSize(StringBuilder, long)}, concatenated by {@code ", "}.
     */
    public static void appendElapsedAndSize(
            StringBuilder buf, long startTimeNanos, long endTimeNanos, long size) {
        appendElapsed(buf, startTimeNanos, endTimeNanos);
        buf.append(", ");
        appendSize(buf, size);
    }

    /**
     * Creates a new {@link StringBuilder} whose content is the human-readable representation of the duration
     * given as {@code elapsed}.
     */
    public static StringBuilder elapsed(long elapsedNanos) {
        final StringBuilder buf = new StringBuilder(16);
        appendElapsed(buf, elapsedNanos);
        return buf;
    }

    /**
     * Creates a new {@link StringBuilder} whose content is the human-readable representation of the duration
     * between the specified {@code startTimeNanos} and {@code endTimeNanos}.
     */
    public static StringBuilder elapsed(long startTimeNanos, long endTimeNanos) {
        return elapsed(endTimeNanos - startTimeNanos);
    }

    /**
     * Creates a new {@link StringBuilder} whose content is the human-readable representation of the byte-unit
     * {@code size}.
     */
    public static StringBuilder size(long size) {
        final StringBuilder buf = new StringBuilder(16);
        appendSize(buf, size);
        return buf;
    }

    /**
     * Similar to {@link #appendElapsedAndSize(StringBuilder, long, long, long)} except that this method
     * creates a new {@link StringBuilder}.
     */
    public static StringBuilder elapsedAndSize(long startTimeNanos, long endTimeNanos, long size) {
        final StringBuilder buf = new StringBuilder(16);
        appendElapsedAndSize(buf, startTimeNanos, endTimeNanos, size);
        return buf;
    }

    private static final DateTimeFormatter dateTimeFormatter =
            new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX")
                                          .toFormatter(Locale.ENGLISH)
                                          .withZone(ZoneId.of("GMT"));

    /**
     * Formats the given epoch time to typical human-readable format "yyyy-MM-dd'T'HH_mm:ss.SSSX" and appends
     * it to the specified {@link StringBuilder}.
     */
    public static void appendEpoch(StringBuilder buf, long timeMillis) {
        buf.append(dateTimeFormatter.format(Instant.ofEpochMilli(timeMillis)))
           .append('(').append(timeMillis).append(')');
    }

    /**
     * Formats the given epoch time to typical human-readable format "yyyy-MM-dd'T'HH:mm:ss.SSSX"
     *
     * @param timeMillis epoch time in ms
     *
     * @return the human readable string representation of the given epoch time
     */
    public static StringBuilder epoch(long timeMillis) {
        // 24 (human readable part) + 3 (parens) + 19 (max digits of a long integer)
        final StringBuilder buf = new StringBuilder(46);
        appendEpoch(buf, timeMillis);
        return buf;
    }
}

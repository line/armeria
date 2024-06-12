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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.ClientConnectionTimings;

/**
 * A utility class to format things as a {@link String} with ease.
 */
public final class TextFormatter {

    /**
     * Creates a new {@link StringBuilder} whose content is the human-readable representation of the duration
     * given as {@code elapsed}.
     */
    public static StringBuilder elapsed(long elapsed, TimeUnit timeUnit) {
        final StringBuilder buf = new StringBuilder(16);
        appendElapsed(buf, timeUnit.toNanos(elapsed));
        return buf;
    }

    /**
     * Creates a new {@link StringBuilder} whose content is the human-readable representation of the duration
     * given as {@code elapsed}.
     *
     * @deprecated Use {@link #elapsed(long, TimeUnit)}.
     */
    @Deprecated
    public static StringBuilder elapsed(long elapsedNanos) {
        return elapsed(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Creates a new {@link StringBuilder} whose content is the human-readable representation of the duration
     * between the specified {@code startTimeNanos} and {@code endTimeNanos}.
     */
    public static StringBuilder elapsed(long startTimeNanos, long endTimeNanos) {
        return elapsed(endTimeNanos - startTimeNanos, TimeUnit.NANOSECONDS);
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
     * Creates a new {@link StringBuilder} whose content is the human-readable representation of the byte-unit
     * {@code size}.
     */
    public static StringBuilder size(long size) {
        final StringBuilder buf = new StringBuilder(16);
        appendSize(buf, size);
        return buf;
    }

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
     * Similar to {@link #appendElapsedAndSize(StringBuilder, long, long, long)} except that this method
     * creates a new {@link StringBuilder}.
     */
    public static StringBuilder elapsedAndSize(long startTimeNanos, long endTimeNanos, long size) {
        final StringBuilder buf = new StringBuilder(16);
        appendElapsedAndSize(buf, startTimeNanos, endTimeNanos, size);
        return buf;
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
     * Formats the given epoch time in microseconds and duration in nanos to the format
     * "epochMicros[elapsedNanos]" and appends it to the specified {@link StringBuilder}.
     * This may be useful to record high-resolution timings such as {@link ClientConnectionTimings}.
     */
    @UnstableApi
    public static void appendEpochAndElapsed(StringBuilder buf, long epochMicros, long elapsedNanos) {
        buf.append(dateTimeMicrosecondFormatter.format(getInstantFromMicros(epochMicros))).append('[');
        appendElapsed(buf, elapsedNanos);
        buf.append(']');
    }

    private static Instant getInstantFromMicros(long microsSinceEpoch) {
        return Instant.EPOCH.plus(microsSinceEpoch, ChronoUnit.MICROS);
    }

    private static final DateTimeFormatter dateTimeFormatter =
            new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX")
                                          .toFormatter(Locale.ENGLISH)
                                          .withZone(ZoneId.of("GMT"));

    private static final DateTimeFormatter dateTimeMicrosecondFormatter =
            new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSX")
                                          .toFormatter(Locale.ENGLISH)
                                          .withZone(ZoneId.of("GMT"));

    /**
     * Formats the given epoch time in milliseconds to typical human-readable format
     * "yyyy-MM-dd'T'HH:mm:ss.SSSX".
     *
     * @param timeMillis epoch time in milliseconds
     *
     * @return the human readable string representation of the given epoch time
     */
    public static StringBuilder epochMillis(long timeMillis) {
        // 24 (human readable part) + 2 (parens) + 19 (max digits of a long integer)
        final StringBuilder buf = new StringBuilder(45);
        appendEpochMillis(buf, timeMillis);
        return buf;
    }

    /**
     * Formats the given epoch time in milliseconds to typical human-readable format
     * "yyyy-MM-dd'T'HH_mm:ss.SSSX" and appends it to the specified {@link StringBuilder}.
     */
    public static void appendEpochMillis(StringBuilder buf, long timeMillis) {
        buf.append(dateTimeFormatter.format(Instant.ofEpochMilli(timeMillis)))
           .append('(').append(timeMillis).append(')');
    }

    /**
     * Formats the given epoch time in microseconds to typical human-readable format
     * "yyyy-MM-dd'T'HH:mm:ss.SSSX".
     *
     * @param timeMicros epoch time in microseconds
     *
     * @return the human readable string representation of the given epoch time
     */
    public static StringBuilder epochMicros(long timeMicros) {
        // 24 (human readable part) + 2 (parens) + 19 (max digits of a long integer)
        final StringBuilder buf = new StringBuilder(45);
        appendEpochMicros(buf, timeMicros);
        return buf;
    }

    /**
     * Formats the given epoch time in microseconds to typical human-readable format
     * "yyyy-MM-dd'T'HH_mm:ss.SSSX" and appends it to the specified {@link StringBuilder}.
     */
    public static void appendEpochMicros(StringBuilder buf, long timeMicros) {
        buf.append(dateTimeFormatter.format(Instant.ofEpochMilli(TimeUnit.MICROSECONDS.toMillis(timeMicros))))
           .append('(').append(timeMicros).append(')');
    }

    /**
     * Formats the given {@link SocketAddress}. The difference from {@link InetSocketAddress#toString()} is
     * that it does not format a host name if it's not available or it's same with the IP address.
     */
    public static StringBuilder socketAddress(@Nullable SocketAddress addr) {
        final StringBuilder buf = new StringBuilder(32);
        appendSocketAddress(buf, addr);
        return buf;
    }

    /**
     * Formats the given {@link SocketAddress}. The difference from {@link InetSocketAddress#toString()} is
     * that it does not format a host name if it's not available or it's same with the IP address.
     */
    public static void appendSocketAddress(StringBuilder buf, @Nullable SocketAddress addr) {
        if (!(addr instanceof InetSocketAddress) || addr instanceof DomainSocketAddress) {
            buf.append(addr);
            return;
        }

        final InetSocketAddress isa = (InetSocketAddress) addr;
        final String host = isa.getHostString();
        final InetAddress resolvedAddr = isa.getAddress();
        final String ip = resolvedAddr != null ? resolvedAddr.getHostAddress() : null;
        if (host != null) {
            if (ip != null) {
                if (host.equals(ip)) {
                    buf.append(ip);
                } else {
                    buf.append(host).append('/').append(ip);
                }
            } else {
                buf.append(host);
            }
        } else {
            buf.append(ip);
        }
        buf.append(':').append(isa.getPort());
    }

    /**
     * Formats the given {@link InetAddress}. The difference from {@link InetAddress#toString()} is
     * that it does not format a host name if it's not available or it's same with the IP address.
     */
    public static StringBuilder inetAddress(@Nullable InetAddress addr) {
        final StringBuilder buf = new StringBuilder(32);
        appendInetAddress(buf, addr);
        return buf;
    }

    /**
     * Formats the given {@link InetAddress}. The difference from {@link InetAddress#toString()} is
     * that it does not format a host name if it's not available or it's same with the IP address.
     */
    public static void appendInetAddress(StringBuilder buf, @Nullable InetAddress addr) {
        if (addr == null) {
            buf.append("null");
            return;
        }

        final String str = addr.toString();
        final int slashPos = str.indexOf('/');
        if (slashPos < 0) {
            // Slash is not found; append as-is.
            buf.append(str);
            return;
        }

        if (slashPos == 0) {
            // Slash is the first character; append without the leading slash.
            buf.append(str, 1, str.length());
            return;
        }

        if (slashPos * 2 + 1 != str.length()) {
            // The host name and IP address have different lengths; append as-is.
            buf.append(str);
            return;
        }

        for (int i = 0; i < slashPos; i++) {
            if (str.charAt(i) != str.charAt(i + slashPos + 1)) {
                // The host name and IP address differ; append as-is.
                buf.append(str);
                return;
            }
        }

        // The host name and IP address equals; append only the first part.
        buf.append(str, 0, slashPos);
    }

    private TextFormatter() {}
}

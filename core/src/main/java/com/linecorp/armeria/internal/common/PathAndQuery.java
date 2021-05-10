/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import static io.netty.util.internal.StringUtil.decodeHexNibble;
import static java.util.Objects.requireNonNull;

import java.util.BitSet;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.internal.common.metric.CaffeineMetricSupport;

import io.micrometer.core.instrument.MeterRegistry;
import it.unimi.dsi.fastutil.Arrays;
import it.unimi.dsi.fastutil.bytes.ByteArrays;

/**
 * A parser of the raw path and query components of an HTTP path. Performs validation and allows caching of
 * results.
 */
public final class PathAndQuery {

    private static final PathAndQuery ROOT_PATH_QUERY = new PathAndQuery("/", null);

    private static final BitSet ALLOWED_PATH_CHARS = new BitSet();
    private static final BitSet ALLOWED_QUERY_CHARS = new BitSet();

    private static final int PERCENT_ENCODING_MARKER = 0xFF;

    private static final byte[] RAW_CHAR_TO_MARKER = new byte[256];
    private static final String[] MARKER_TO_PERCENT_ENCODED_CHAR = new String[256];

    static {
        final String allowedPathChars =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#[]@!$&'()*+,;=";
        for (int i = 0; i < allowedPathChars.length(); i++) {
            ALLOWED_PATH_CHARS.set(allowedPathChars.charAt(i));
        }

        final String allowedQueryChars =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#[]@!$&'()*,;=";
        for (int i = 0; i < allowedQueryChars.length(); i++) {
            ALLOWED_QUERY_CHARS.set(allowedQueryChars.charAt(i));
        }

        for (final ReservedChar reservedChar : ReservedChar.values()) {
            RAW_CHAR_TO_MARKER[reservedChar.rawChar] = reservedChar.marker;
            MARKER_TO_PERCENT_ENCODED_CHAR[reservedChar.marker] = reservedChar.percentEncodedChar;
        }
    }

    private static final Bytes EMPTY_QUERY = new Bytes(0);
    private static final Bytes ROOT_PATH = new Bytes(new byte[] { '/' });

    @Nullable
    private static final Cache<String, PathAndQuery> CACHE =
            Flags.parsedPathCacheSpec() != null ? buildCache(Flags.parsedPathCacheSpec()) : null;

    private static Cache<String, PathAndQuery> buildCache(String spec) {
        return Caffeine.from(spec).build();
    }

    public static void registerMetrics(MeterRegistry registry, MeterIdPrefix idPrefix) {
        if (CACHE != null) {
            CaffeineMetricSupport.setup(registry, idPrefix, CACHE);
        }
    }

    /**
     * Clears the currently cached parsed paths. Only for use in tests.
     */
    @VisibleForTesting
    public static void clearCachedPaths() {
        requireNonNull(CACHE, "CACHE");
        CACHE.asMap().clear();
    }

    /**
     * Returns paths that have had their parse result cached. Only for use in tests.
     */
    @VisibleForTesting
    public static Set<String> cachedPaths() {
        requireNonNull(CACHE, "CACHE");
        return CACHE.asMap().keySet();
    }

    /**
     * Validates the {@link String} that contains an absolute path and a query, and splits them into
     * the path part and the query part. If the path is usable (e.g., can be served a successful response from
     * the server and doesn't have variable path parameters), {@link PathAndQuery#storeInCache(String)} should
     * be called to cache the parsing result for faster future invocations.
     *
     * @return a {@link PathAndQuery} with the absolute path and query, or {@code null} if the specified
     *         {@link String} is not an absolute path or invalid.
     */
    @Nullable
    public static PathAndQuery parse(@Nullable String rawPath) {
        if (CACHE != null && rawPath != null) {
            final PathAndQuery parsed = CACHE.getIfPresent(rawPath);
            if (parsed != null) {
                return parsed;
            }
        }
        return splitPathAndQuery(rawPath);
    }

    /**
     * Stores this {@link PathAndQuery} into cache for the given raw path. This should be used by callers when
     * the parsed result was valid (e.g., when a server is able to successfully handle the parsed path).
     */
    public void storeInCache(@Nullable String rawPath) {
        if (CACHE != null && !cached && rawPath != null) {
            cached = true;
            CACHE.put(rawPath, this);
        }
    }

    private final String path;
    @Nullable
    private final String query;

    private boolean cached;

    private PathAndQuery(String path, @Nullable String query) {
        this.path = path;
        this.query = query;
    }

    public String path() {
        return path;
    }

    @Nullable
    public String query() {
        return query;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof PathAndQuery)) {
            return false;
        }

        final PathAndQuery that = (PathAndQuery) o;
        return Objects.equals(path, that.path) &&
               Objects.equals(query, that.query);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, query);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("path", path)
                          .add("query", query)
                          .toString();
    }

    @Nullable
    private static PathAndQuery splitPathAndQuery(@Nullable final String pathAndQuery) {
        final Bytes path;
        final Bytes query;

        if (pathAndQuery == null) {
            return ROOT_PATH_QUERY;
        }

        // Split by the first '?'.
        final int queryPos = pathAndQuery.indexOf('?');
        if (queryPos >= 0) {
            if ((path = decodePercentsAndEncodeToUtf8(
                    pathAndQuery, 0, queryPos, true)) == null) {
                return null;
            }
            if ((query = decodePercentsAndEncodeToUtf8(
                    pathAndQuery, queryPos + 1, pathAndQuery.length(), false)) == null) {
                return null;
            }
        } else {
            if ((path = decodePercentsAndEncodeToUtf8(
                    pathAndQuery, 0, pathAndQuery.length(), true)) == null) {
                return null;
            }
            query = null;
        }

        if (path.data[0] != '/') {
            // Do not accept a relative path.
            return null;
        }

        // Reject the prohibited patterns.
        if (pathContainsDoubleDots(path)) {
            return null;
        }

        return new PathAndQuery(encodeToPercents(path, true),
                                query != null ? encodeToPercents(query, false) : null);
    }

    @Nullable
    private static Bytes decodePercentsAndEncodeToUtf8(String value, int start, int end, boolean isPath) {
        final int length = end - start;
        if (length == 0) {
            return isPath ? ROOT_PATH : EMPTY_QUERY;
        }

        final Bytes buf = new Bytes(Math.max(length * 3 / 2, 4));
        boolean wasSlash = false;
        for (final CodePointIterator i = new CodePointIterator(value, start, end);
             i.hasNextCodePoint();/* noop */) {
            final int pos = i.position();
            final int cp = i.nextCodePoint();

            if (cp == '%') {
                final int hexEnd = pos + 3;
                if (hexEnd > end) {
                    // '%' or '%x' (must be followed by two hexadigits)
                    return null;
                }

                final int digit1 = decodeHexNibble(value.charAt(pos + 1));
                final int digit2 = decodeHexNibble(value.charAt(pos + 2));
                if (digit1 < 0 || digit2 < 0) {
                    // The first or second digit is not hexadecimal.
                    return null;
                }

                final int decoded = (digit1 << 4) | digit2;
                if (isPath) {
                    if (appendOneByte(buf, decoded, wasSlash, isPath)) {
                        wasSlash = decoded == '/';
                    } else {
                        return null;
                    }
                } else {
                    // If query:
                    final byte marker = RAW_CHAR_TO_MARKER[decoded];
                    if (marker != 0) {
                        // Insert a special mark so we can distinguish a raw character and percent-encoded
                        // character in a query string, such as '&' and '%26'.
                        // We will encode this mark back into a percent-encoded character later.
                        buf.ensure(2);
                        buf.add((byte) PERCENT_ENCODING_MARKER);
                        buf.add(marker);
                        wasSlash = false;
                    } else if (appendOneByte(buf, decoded, wasSlash, isPath)) {
                        wasSlash = decoded == '/';
                    } else {
                        return null;
                    }
                }

                i.position(hexEnd);
                continue;
            }

            if (cp == '+' && !isPath) {
                buf.ensure(1);
                buf.add((byte) ' ');
                wasSlash = false;
                continue;
            }

            if (cp <= 0x7F) {
                if (!appendOneByte(buf, cp, wasSlash, isPath)) {
                    return null;
                }
                wasSlash = cp == '/';
                continue;
            }

            if (cp <= 0x7ff) {
                buf.ensure(2);
                buf.add((byte) ((cp >>> 6) | 0b110_00000));
                buf.add((byte) (cp & 0b111111 | 0b10_000000));
            } else if (cp <= 0xffff) {
                buf.ensure(3);
                buf.add((byte) ((cp >>> 12) | 0b1110_0000));
                buf.add((byte) (((cp >>> 6) & 0b111111) | 0b10_000000));
                buf.add((byte) ((cp & 0b111111) | 0b10_000000));
            } else if (cp <= 0x1fffff) {
                buf.ensure(4);
                buf.add((byte) ((cp >>> 18) | 0b11110_000));
                buf.add((byte) (((cp >>> 12) & 0b111111) | 0b10_000000));
                buf.add((byte) (((cp >>> 6) & 0b111111) | 0b10_000000));
                buf.add((byte) ((cp & 0b111111) | 0b10_000000));
            } else if (cp <= 0x3ffffff) {
                // A valid unicode character will never reach here, but for completeness.
                // http://unicode.org/mail-arch/unicode-ml/Archives-Old/UML018/0330.html
                buf.ensure(5);
                buf.add((byte) ((cp >>> 24) | 0b111110_00));
                buf.add((byte) (((cp >>> 18) & 0b111111) | 0b10_000000));
                buf.add((byte) (((cp >>> 12) & 0b111111) | 0b10_000000));
                buf.add((byte) (((cp >>> 6) & 0b111111) | 0b10_000000));
                buf.add((byte) ((cp & 0b111111) | 0b10_000000));
            } else {
                // A valid unicode character will never reach here, but for completeness.
                // http://unicode.org/mail-arch/unicode-ml/Archives-Old/UML018/0330.html
                buf.ensure(6);
                buf.add((byte) ((cp >>> 30) | 0b1111110_0));
                buf.add((byte) (((cp >>> 24) & 0b111111) | 0b10_000000));
                buf.add((byte) (((cp >>> 18) & 0b111111) | 0b10_000000));
                buf.add((byte) (((cp >>> 12) & 0b111111) | 0b10_000000));
                buf.add((byte) (((cp >>> 6) & 0b111111) | 0b10_000000));
                buf.add((byte) ((cp & 0b111111) | 0b10_000000));
            }

            wasSlash = false;
        }

        return buf;
    }

    private static boolean appendOneByte(Bytes buf, int cp, boolean wasSlash, boolean isPath) {
        if (cp == 0x7F) {
            // Reject the control character: 0x7F
            return false;
        }

        if (cp >>> 5 == 0) {
            // Reject the control characters: 0x00..0x1F
            if (isPath) {
                return false;
            } else if (cp != 0x0A && cp != 0x0D && cp != 0x09) {
                // .. except 0x0A (LF), 0x0D (CR) and 0x09 (TAB) because they are used in a form.
                return false;
            }
        }

        if (cp == '/' && isPath) {
            if (!wasSlash) {
                buf.ensure(1);
                buf.add((byte) '/');
            } else {
                // Remove the consecutive slashes: '/path//with///consecutive////slashes'.
            }
        } else {
            buf.ensure(1);
            buf.add((byte) cp);
        }

        return true;
    }

    private static boolean pathContainsDoubleDots(Bytes path) {
        final int length = path.length;
        byte b0 = 0;
        byte b1 = 0;
        byte b2 = '/';
        for (int i = 1; i < length; i++) {
            final byte b3 = path.data[i];
            if (b3 == '/' && b2 == '.' && b1 == '.' && b0 == '/') {
                return true;
            }
            b0 = b1;
            b1 = b2;
            b2 = b3;
        }

        return b0 == '/' && b1 == '.' && b2 == '.';
    }

    private static String encodeToPercents(Bytes value, boolean isPath) {
        final BitSet allowedChars = isPath ? ALLOWED_PATH_CHARS : ALLOWED_QUERY_CHARS;
        final int length = value.length;
        boolean needsEncoding = false;
        for (int i = 0; i < length; i++) {
            if (!allowedChars.get(value.data[i] & 0xFF)) {
                needsEncoding = true;
                break;
            }
        }

        if (!needsEncoding) {
            // Deprecated, but it fits perfect for our use case.
            // noinspection deprecation
            return new String(value.data, 0, 0, length);
        }

        final StringBuilder buf = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            final int b = value.data[i] & 0xFF;

            if (b == PERCENT_ENCODING_MARKER && (i + 1) < length) {
                final int marker = value.data[i + 1] & 0xFF;
                final String percentEncodedChar = MARKER_TO_PERCENT_ENCODED_CHAR[marker];
                if (percentEncodedChar != null) {
                    buf.append(percentEncodedChar);
                    i++;
                    continue;
                }
            }

            if (allowedChars.get(b)) {
                buf.append((char) b);
            } else if (b == ' ') {
                if (isPath) {
                    buf.append("%20");
                } else {
                    buf.append('+');
                }
            } else {
                buf.append('%');
                appendHexNibble(buf, b >>> 4);
                appendHexNibble(buf, b & 0xF);
            }
        }

        return buf.toString();
    }

    private static void appendHexNibble(StringBuilder buf, int nibble) {
        if (nibble < 10) {
            buf.append((char) ('0' + nibble));
        } else {
            buf.append((char) ('A' + nibble - 10));
        }
    }

    private static final class Bytes {
        byte[] data;
        int length;

        Bytes(int initialCapacity) {
            data = new byte[initialCapacity];
        }

        Bytes(byte[] data) {
            this.data = data;
            length = data.length;
        }

        void add(byte b) {
            data[length++] = b;
        }

        void ensure(int numBytes) {
            int newCapacity = length + numBytes;
            if (newCapacity <= data.length) {
                return;
            }

            newCapacity =
                    (int) Math.max(Math.min((long) data.length + (data.length >> 1), Arrays.MAX_ARRAY_SIZE),
                                   newCapacity);

            data = ByteArrays.forceCapacity(data, newCapacity, length);
        }
    }

    private static final class CodePointIterator {
        private final CharSequence str;
        private final int end;
        private int pos;

        CodePointIterator(CharSequence str, int start, int end) {
            this.str = str;
            this.end = end;
            pos = start;
        }

        int position() {
            return pos;
        }

        void position(int pos) {
            this.pos = pos;
        }

        boolean hasNextCodePoint() {
            return pos < end;
        }

        int nextCodePoint() {
            assert pos < end;

            final char c1 = str.charAt(pos++);
            if (Character.isHighSurrogate(c1) && pos < end) {
                final char c2 = str.charAt(pos);
                if (Character.isLowSurrogate(c2)) {
                    pos++;
                    return Character.toCodePoint(c1, c2);
                }
            }

            return c1;
        }
    }

    /**
     * Reserved characters which require percent-encoding. These values are only used for constructing
     * {@link #RAW_CHAR_TO_MARKER} and {@link #MARKER_TO_PERCENT_ENCODED_CHAR} mapping tables.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc3986#section-2.2">RFC 3986, section 2.2</a>
     */
    private enum ReservedChar {
        GEN_DELIM_01(':', "%3A", (byte) 0x01),
        GEN_DELIM_02('/', "%2F", (byte) 0x02),
        GEN_DELIM_03('?', "%3F", (byte) 0x03),
        GEN_DELIM_04('#', "%23", (byte) 0x04),
        GEN_DELIM_05('[', "%5B", (byte) 0x05),
        GEN_DELIM_06(']', "%5D", (byte) 0x06),
        GEN_DELIM_07('@', "%40", (byte) 0x07),

        SUB_DELIM_01('!', "%21", (byte) 0x11),
        SUB_DELIM_02('$', "%24", (byte) 0x12),
        SUB_DELIM_03('&', "%26", (byte) 0x13),
        SUB_DELIM_04('\'', "%27", (byte) 0x14),
        SUB_DELIM_05('(', "%28", (byte) 0x15),
        SUB_DELIM_06(')', "%29", (byte) 0x16),
        SUB_DELIM_07('*', "%2A", (byte) 0x17),
        SUB_DELIM_08('+', "%2B", (byte) 0x18),
        SUB_DELIM_09(',', "%2C", (byte) 0x19),
        SUB_DELIM_10(';', "%3B", (byte) 0x1A),
        SUB_DELIM_11('=', "%3D", (byte) 0x1B);

        private final int rawChar;
        private final String percentEncodedChar;
        private final byte marker;

        ReservedChar(int rawChar, String percentEncodedChar, byte marker) {
            this.rawChar = rawChar;
            this.percentEncodedChar = percentEncodedChar;
            this.marker = marker;
        }
    }
}

/*
 * Copyright 2020 LINE Corporation
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
/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.common;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.multipart.MultipartFilenameDecodingMode;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * Representation of the Content-Disposition type and parameters as defined in RFC 6266.
 *
 * @author Sebastien Deleuze
 * @author Juergen Hoeller
 * @author Rossen Stoyanchev
 * @author Sergey Tsypanov
 * @see <a href="https://datatracker.ietf.org/doc/rfc6266/">RFC 6266</a>
 */
public final class ContentDisposition {

    // Forked from https://github.com/spring-projects/spring-framework/blob/e5fccd1fbbf09f1e253b10ebfc12ad339d0196b5/spring-web/src/main/java/org/springframework/http/ContentDisposition.java

    private static final Logger logger = LoggerFactory.getLogger(ContentDisposition.class);

    private static final ContentDisposition EMPTY = new ContentDisposition("", null, null, null);

    private static final Pattern BASE64_ENCODED_PATTERN =
            Pattern.compile("=\\?([0-9a-zA-Z-_]+)\\?B\\?([+/0-9a-zA-Z]+=*)\\?=");

    // Printable ASCII other than "?" or SPACE
    private static final Pattern QUOTED_PRINTABLE_ENCODED_PATTERN =
            Pattern.compile("=\\?([0-9a-zA-Z-_]+)\\?Q\\?([!->@-~]+)\\?=");

    private static final MultipartFilenameDecodingMode MULTIPART_FILENAME_DECODING_MODE =
            Flags.defaultMultipartFilenameDecodingMode();

    private static final BitSet PRINTABLE = new BitSet(256);

    static {
        // RFC 2045, Section 6.7, and RFC 2047, Section 4.2
        for (int i = 33; i <= 126; i++) {
            PRINTABLE.set(i);
        }
        PRINTABLE.set(34, false); // "
        PRINTABLE.set(61, false); // =
        PRINTABLE.set(63, false); // ?
        PRINTABLE.set(95, false); // _
    }

    /**
     * Returns a new {@link ContentDispositionBuilder} with the specified {@code type}.
     *
     * @param type the disposition type like for example {@code inline}, {@code attachment},
     *             or {@code form-data}
     */
    public static ContentDispositionBuilder builder(String type) {
        requireNonNull(type, "type");
        checkArgument(!type.isEmpty(), "type should not be empty");
        return new ContentDispositionBuilder(type);
    }

    /**
     * Returns a new {@link ContentDisposition} with the specified {@code type}.
     *
     * @param type the disposition type like for example {@code inline}, {@code attachment},
     *             or {@code form-data}
     */
    public static ContentDisposition of(String type) {
        return builder(type).build();
    }

    /**
     * Returns a new {@link ContentDisposition} with the specified {@code type} and {@code name}.
     *
     * @param type the disposition type like for example {@code inline}, {@code attachment},
     *             or {@code form-data}
     * @param name the name parameter
     */
    public static ContentDisposition of(String type, String name) {
        return builder(type).name(name).build();
    }

    /**
     * Returns a new {@link ContentDisposition} with the specified {@code type}, {@code name}
     * and {@code filename}.
     *
     * @param type the disposition type like for example {@code inline}, {@code attachment},
     *             or {@code form-data}
     * @param name the name parameter
     * @param filename the filename parameter that will be formatted as quoted-string,
     *                 as defined in RFC 2616, section 2.2, and any quote characters within
     *                 the filename value will be escaped with a backslash,
     *                 e.g. {@code "foo\"bar.txt"} becomes {@code "foo\\\"bar.txt"}
     */
    public static ContentDisposition of(String type, String name, String filename) {
        return builder(type).name(name).filename(filename).build();
    }

    /**
     * Returns an empty {@link ContentDisposition}.
     */
    public static ContentDisposition of() {
        return EMPTY;
    }

    /**
     * Parses a {@code "content-disposition"} header value as defined in RFC 2183.
     *
     * <p>Note that only the US-ASCII, UTF-8 and ISO-8859-1 charsets are supported.
     * If other charsets are specified, ISO-8859-1 will be used instead.
     *
     * @param contentDisposition the {@code "content-disposition"} header value
     * @return the parsed content disposition
     * @see #asHeaderValue()
     */
    public static ContentDisposition parse(String contentDisposition) {
        requireNonNull(contentDisposition, "contentDisposition");
        final List<String> parts = tokenize(contentDisposition);
        final String type = parts.get(0);
        String name = null;
        String filename = null;
        Charset charset = null;
        for (int i = 1; i < parts.size(); i++) {
            final String part = parts.get(i);
            final int eqIndex = part.indexOf('=');
            if (eqIndex != -1) {
                final String attribute = part.substring(0, eqIndex).toLowerCase(Locale.ROOT);
                final String value;
                if (part.startsWith("\"", eqIndex + 1) && part.endsWith("\"")) {
                    value = part.substring(eqIndex + 2, part.length() - 1);
                } else {
                    value = part.substring(eqIndex + 1);
                }

                if ("name".equals(attribute)) {
                    name = value;
                } else if ("filename*".equals(attribute)) {
                    final int idx1 = value.indexOf('\'');
                    final int idx2 = value.indexOf('\'', idx1 + 1);
                    if (idx1 != -1 && idx2 != -1) {
                        final String charsetString = value.substring(0, idx1).trim();
                        charset = Charset.forName(charsetString);
                        if (UTF_8 != charset && ISO_8859_1 != charset) {
                            throw new IllegalArgumentException("Charset must be UTF-8 or ISO-8859-1" +
                                                               " for filename*: " + charsetString);
                        }

                        filename = decodeFilename(value.substring(idx2 + 1), charset);
                    } else {
                        // US ASCII
                        filename = decodeFilename(value, StandardCharsets.US_ASCII);
                    }
                } else if ("filename".equals(attribute) && (filename == null)) {
                    if (value.startsWith("=?")) {
                        Matcher matcher = BASE64_ENCODED_PATTERN.matcher(value);
                        if (matcher.find()) {
                            final Base64.Decoder decoder = Base64.getDecoder();
                            final StringBuilder builder = new StringBuilder();
                            do {
                                charset = Charset.forName(matcher.group(1));
                                final byte[] decoded = decoder.decode(matcher.group(2));
                                builder.append(new String(decoded, charset));
                            }
                            while (matcher.find());

                            filename = builder.toString();
                        } else {
                            matcher = QUOTED_PRINTABLE_ENCODED_PATTERN.matcher(value);
                            if (matcher.find()) {
                                final StringBuilder builder = new StringBuilder();
                                do {
                                    charset = Charset.forName(matcher.group(1));
                                    final String decoded =
                                            decodeQuotedPrintableFilename(matcher.group(2), charset);
                                    builder.append(decoded);
                                }
                                while (matcher.find());

                                filename = builder.toString();
                            } else {
                                filename = value;
                            }
                        }
                    } else if (value.indexOf('\\') != -1) {
                        filename = decodeQuotedPairs(value);
                    } else if (MULTIPART_FILENAME_DECODING_MODE == MultipartFilenameDecodingMode.URL_DECODING) {
                        try {
                            filename = URLDecoder.decode(value, "UTF-8");
                        } catch (Exception e) {
                            logger.debug("Failed to URL decode filename: {}, contentDisposition: {}",
                                         value, contentDisposition, e);
                            filename = value;
                        }
                    } else {
                        filename = value;
                    }
                }
            } else {
                throw new IllegalArgumentException("Invalid content disposition format: " + contentDisposition);
            }
        }
        return new ContentDisposition(type, name, filename, charset);
    }

    private final String type;

    @Nullable
    private final String name;

    @Nullable
    private final String filename;

    @Nullable
    private final Charset charset;

    @Nullable
    private String strVal;

    ContentDisposition(String type, @Nullable String name,
                       @Nullable String filename, @Nullable Charset charset) {
        this.type = type;
        this.name = name;
        this.filename = filename;
        this.charset = charset;
    }

    /**
     * Returns the disposition type, like for example {@code inline}, {@code attachment},
     * {@code form-data}.
     */
    public String type() {
        return type;
    }

    /**
     * Returns the value of the {@code name} parameter, or {@code null} if not defined.
     */
    @Nullable
    public String name() {
        return name;
    }

    /**
     * Returns the value of the {@code filename} parameter (or the value of the
     * {@code filename*} one decoded as defined in the RFC 5987), or {@code null} if not defined.
     */
    @Nullable
    public String filename() {
        return filename;
    }

    /**
     * Returns the charset defined in {@code filename*} parameter, or {@code null} if not defined.
     */
    @Nullable
    public Charset charset() {
        return charset;
    }

    private static List<String> tokenize(String headerValue) {
        int index = headerValue.indexOf(';');
        final String type = (index >= 0 ? headerValue.substring(0, index) : headerValue).trim();
        checkArgument(!type.isEmpty(), "Content-Disposition header must not be empty");

        final ImmutableList.Builder<String> parts = ImmutableList.builderWithExpectedSize(4);
        parts.add(type);
        if (index >= 0) {
            do {
                int nextIndex = index + 1;
                boolean quoted = false;
                boolean escaped = false;
                while (nextIndex < headerValue.length()) {
                    final char ch = headerValue.charAt(nextIndex);
                    if (ch == ';') {
                        if (!quoted) {
                            break;
                        }
                    } else if (!escaped && ch == '"') {
                        quoted = !quoted;
                    }
                    escaped = !escaped && ch == '\\';
                    nextIndex++;
                }
                final String part = headerValue.substring(index + 1, nextIndex).trim();
                if (!part.isEmpty()) {
                    parts.add(part);
                }
                index = nextIndex;
            }
            while (index < headerValue.length());
        }
        return parts.build();
    }

    /**
     * Decodes the given header field param as described in RFC 5987.
     *
     * <p>Only the US-ASCII, UTF-8 and ISO-8859-1 charsets are supported.
     *
     * @param filename the filename
     * @param charset the charset for the filename
     * @return the encoded header field param
     *
     * @see <a href="https://datatracker.ietf.org/doc/rfc5987/">RFC 5987</a>
     */
    private static String decodeFilename(String filename, Charset charset) {
        final byte[] value = filename.getBytes(charset);
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int index = 0;
        while (index < value.length) {
            final byte b = value[index];
            if (isRFC5987AttrChar(b)) {
                baos.write((char) b);
                index++;
            } else if (b == '%' && index < value.length - 2) {
                final char[] array = {(char) value[index + 1], (char) value[index + 2]};
                try {
                    baos.write(Integer.parseInt(String.valueOf(array), 16));
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException(
                            "Invalid filename header field parameter format (as defined in RFC 5987): " +
                            filename + " (charset: " + charset + ')', ex);
                }
                index += 3;
            } else {
                throw new IllegalArgumentException(
                        "Invalid filename header field parameter format (as defined in RFC 5987): " +
                        filename + " (charset: " + charset + ')');
            }
        }
        return copyToString(baos, charset);
    }

    private static String copyToString(ByteArrayOutputStream baos, Charset charset) {
        try {
            return baos.toString(charset.name());
        } catch (UnsupportedEncodingException e) {
            // Should never reach here.
            // The charset should be either UTF-8 or ISO-8859-1.
            throw new Error();
        }
    }

    private static boolean isRFC5987AttrChar(byte c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
               c == '!' || c == '#' || c == '$' || c == '&' || c == '+' || c == '-' ||
               c == '.' || c == '^' || c == '_' || c == '`' || c == '|' || c == '~';
    }

    private static void escapeQuotationsInFilename(StringBuilder sb, String filename) {
        if (filename.indexOf('"') == -1 && filename.indexOf('\\') == -1) {
            sb.append(filename);
            return;
        }

        boolean escaped = false;
        for (char c : filename.toCharArray()) {
            if (!escaped && c == '"') {
                sb.append("\\\"");
            } else {
                sb.append(c);
            }
            escaped = !escaped && c == '\\';
        }
        // Remove backslash at the end..
        if (escaped) {
            sb.deleteCharAt(sb.length() - 1);
        }
    }

    /**
     * Encodes the given header field param as describe in RFC 5987.
     *
     * @param input the header field param
     * @param charset the charset of the header field param string,
     *                only the US-ASCII, UTF-8 and ISO-8859-1 charsets are supported
     * @see <a href="https://datatracker.ietf.org/doc/rfc5987/">RFC 5987</a>
     */
    private static void encodeFilename(StringBuilder sb, String input, Charset charset) {
        final byte[] source = input.getBytes(charset);
        sb.append(charset.name());
        sb.append("''");
        for (byte b : source) {
            if (isRFC5987AttrChar(b)) {
                sb.append((char) b);
            } else {
                sb.append('%');
                final char hex1 = Ascii.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16));
                final char hex2 = Ascii.toUpperCase(Character.forDigit(b & 0xF, 16));
                sb.append(hex1);
                sb.append(hex2);
            }
        }
    }

    private static String decodeQuotedPrintableFilename(String filename, Charset charset) {
        final byte[] value = filename.getBytes(StandardCharsets.US_ASCII);
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int index = 0;
        while (index < value.length) {
            final byte b = value[index];
            if (b == '_') { // RFC 2047, section 4.2, rule (2)
                baos.write(' ');
                index++;
            } else if (b == '=' && index < value.length - 2) {
                final char[] array = {(char) value[index + 1], (char) value[index + 2]};
                baos.write(Integer.parseInt(String.valueOf(array), 16));
                index += 3;
            } else {
                baos.write(b);
                index++;
            }
        }
        return copyToString(baos, charset);
    }

    private static String decodeQuotedPairs(String filename) {
        final StringBuilder sb = new StringBuilder();
        final int length = filename.length();
        for (int i = 0; i < length; i++) {
            final char c = filename.charAt(i);
            if (filename.charAt(i) == '\\' && i + 1 < length) {
                i++;
                final char next = filename.charAt(i);
                if (next != '"' && next != '\\') {
                    sb.append(c);
                }
                sb.append(next);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ContentDisposition)) {
            return false;
        }
        final ContentDisposition that = (ContentDisposition) other;
        return type.equals(that.type) &&
               Objects.equals(name, that.name) &&
               Objects.equals(filename, that.filename) &&
               Objects.equals(charset, that.charset);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, name, filename, charset);
    }

    /**
     * Returns the header value for this content disposition as defined in RFC 6266.
     *
     * @see #parse(String)
     */
    public String asHeaderValue() {
        if (strVal != null) {
            return strVal;
        }

        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder sb = tempThreadLocals.stringBuilder();
            sb.append(type);

            if (name != null) {
                sb.append("; name=\"");
                sb.append(name).append('\"');
            }
            if (filename != null) {
                if (charset == null || StandardCharsets.US_ASCII.equals(charset)) {
                    sb.append("; filename=\"");
                    sb.append(encodeQuotedPairs(this.filename)).append('\"');
                } else {
                    sb.append("; filename=\"");
                    sb.append(encodeQuotedPrintableFilename(filename, charset)).append('\"');
                    sb.append("; filename*=");
                    sb.append(encodeRfc5987Filename(filename, charset));
                }
            }
            return strVal = sb.toString();
        }
    }

    private static String encodeQuotedPairs(String filename) {
        if (filename.indexOf('"') == -1 && filename.indexOf('\\') == -1) {
            return filename;
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < filename.length(); i++) {
            final char c = filename.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Encode the given header field param as described in RFC 2047.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc2047">RFC 2047</a>
     */
    private static String encodeQuotedPrintableFilename(String filename, Charset charset) {
        final byte[] source = filename.getBytes(charset);
        final StringBuilder sb = new StringBuilder(source.length << 1);
        sb.append("=?");
        sb.append(charset.name());
        sb.append("?Q?");
        for (byte b : source) {
            if (b == 32) { // RFC 2047, section 4.2, rule (2)
                sb.append('_');
            } else if (isPrintable(b)) {
                sb.append((char) b);
            } else {
                sb.append('=');
                sb.append(String.format("%02X", b & 0xFF));
            }
        }
        sb.append("?=");
        return sb.toString();
    }

    private static boolean isPrintable(byte c) {
        int b = c;
        if (b < 0) {
            b = 256 + b;
        }
        return PRINTABLE.get(b);
    }

    /**
     * Encode the given header field param as describe in RFC 5987.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc5987">RFC 5987</a>
     */
    private static String encodeRfc5987Filename(String input, Charset charset) {
        final byte[] source = input.getBytes(charset);
        final StringBuilder sb = new StringBuilder(source.length << 1);
        sb.append(charset.name());
        sb.append("''");
        for (byte b : source) {
            if (isRFC5987AttrChar(b)) {
                sb.append((char) b);
            } else {
                sb.append('%');
                sb.append(String.format("%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }

    /**
     * Returns the header value for this content disposition as defined in RFC 6266.
     */
    @Override
    public String toString() {
        return asHeaderValue();
    }
}

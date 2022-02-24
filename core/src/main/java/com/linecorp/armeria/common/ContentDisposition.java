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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;
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

    // Forked from https://github.com/spring-projects/spring-framework/blob/d9ccd618ea9cbf339eb5639d24d5a5fabe8157b5/spring-web/src/main/java/org/springframework/http/ContentDisposition.java

    private static final ContentDisposition EMPTY = new ContentDisposition("", null, null, null);

    private static final Map<String, Charset> supportedCharsets =
            ImmutableMap.of("utf-8", UTF_8, "iso-8859-1", ISO_8859_1);

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
                final String attribute = part.substring(0, eqIndex);
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
                        charset = supportedCharsets.getOrDefault(Ascii.toLowerCase(charsetString), ISO_8859_1);
                        filename = decodeFilename(value.substring(idx2 + 1), charset);
                    } else {
                        // US ASCII
                        filename = decodeFilename(value, StandardCharsets.US_ASCII);
                    }
                } else if ("filename".equals(attribute) && (filename == null)) {
                    filename = value;
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
                    escapeQuotationsInFilename(sb, filename);
                    sb.append('\"');
                } else {
                    sb.append("; filename*=");
                    encodeFilename(sb, filename, charset);
                }
            }
            return strVal = sb.toString();
        }
    }

    /**
     * Returns the header value for this content disposition as defined in RFC 6266.
     */
    @Override
    public String toString() {
        return asHeaderValue();
    }
}

/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.http;

import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Formatter;
import java.util.Locale;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

/**
 * HTTP/2 data.
 */
public interface HttpData extends HttpObject {

    /**
     * Empty HTTP/2 data.
     */
    HttpData EMPTY_DATA = DefaultHttpData.EMPTY_DATA;

    /**
     * Creates a new instance from the specified byte array. The array is not copied; any changes made in the
     * array later will be visible to {@link HttpData}.
     *
     * @return a new {@link HttpData}. {@link #EMPTY_DATA} if the length of the specified array is 0.
     */
    static HttpData of(byte[] data) {
        requireNonNull(data, "data");
        if (data.length == 0) {
            return EMPTY_DATA;
        }

        return new DefaultHttpData(data, 0, data.length);
    }

    /**
     * Creates a new instance from the specified byte array, {@code offset} and {@code length}.
     * The array is not copied; any changes made in the array later will be visible to {@link HttpData}.
     *
     * @return a new {@link HttpData}. {@link #EMPTY_DATA} if {@code length} is 0.
     *
     * @throws ArrayIndexOutOfBoundsException if {@code offset} and {@code length} are out of bounds
     */
    static HttpData of(byte[] data, int offset, int length) {
        requireNonNull(data);
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new ArrayIndexOutOfBoundsException(
                    "offset: " + offset + ", length: " + length + ", data.length: " + data.length);
        }
        if (length == 0) {
            return EMPTY_DATA;
        }

        return new DefaultHttpData(data, offset, length);
    }

    /**
     * Converts the specified {@code text} into an {@link HttpData}.
     *
     * @param charset the {@link Charset} of the encoded {@code text}
     * @param text the {@link String} to convert
     *
     * @return a new {@link HttpData}. {@link #EMPTY_DATA} if the length of {@code text} is 0.
     */
    static HttpData of(Charset charset, String text) {
        requireNonNull(charset, "charset");
        requireNonNull(text, "text");
        if (text.isEmpty()) {
            return EMPTY_DATA;
        }

        return of(text.getBytes(charset));
    }

    /**
     * Converts the specified Netty {@link ByteBuf} into an {@link HttpData}. Unlike {@link #of(byte[])}, this
     * method makes a copy of the {@link ByteBuf}.
     *
     * @return a new {@link HttpData}. {@link #EMPTY_DATA} if the readable bytes of {@code buf} is 0.
     */
    static HttpData of(ByteBuf buf) {
        requireNonNull(buf, "buf");
        if (!buf.isReadable()) {
            return EMPTY_DATA;
        }
        return of(ByteBufUtil.getBytes(buf));
    }

    /**
     * Converts the specified formatted string into an {@link HttpData}. The string is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     *
     * @param charset the {@link Charset} of the encoded string
     * @param format {@linkplain Formatter the format string} of the response content
     * @param args the arguments referenced by the format specifiers in the format string
     *
     * @return a new {@link HttpData}. {@link #EMPTY_DATA} if {@code format} is empty.
     */
    static HttpData of(Charset charset, String format, Object... args) {
        requireNonNull(charset, "charset");
        requireNonNull(format, "format");
        requireNonNull(args, "args");

        if (format.isEmpty()) {
            return EMPTY_DATA;
        }

        return of(String.format(Locale.ENGLISH, format, args).getBytes(charset));
    }

    /**
     * Converts the specified {@code text} into a UTF-8 {@link HttpData}.
     *
     * @param text the {@link String} to convert
     *
     * @return a new {@link HttpData}. {@link #EMPTY_DATA} if the length of {@code text} is 0.
     */
    static HttpData ofUtf8(String text) {
        return of(StandardCharsets.UTF_8, text);
    }

    /**
     * Converts the specified formatted string into a UTF-8 {@link HttpData}. The string is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     *
     * @param format {@linkplain Formatter the format string} of the response content
     * @param args the arguments referenced by the format specifiers in the format string
     *
     * @return a new {@link HttpData}. {@link #EMPTY_DATA} if {@code format} is empty.
     */
    static HttpData ofUtf8(String format, Object... args) {
        return of(StandardCharsets.UTF_8, format, args);
    }

    /**
     * Converts the specified {@code text} into a US-ASCII {@link HttpData}.
     *
     * @param text the {@link String} to convert
     *
     * @return a new {@link HttpData}. {@link #EMPTY_DATA} if the length of {@code text} is 0.
     */
    static HttpData ofAscii(String text) {
        return of(StandardCharsets.US_ASCII, text);
    }

    /**
     * Converts the specified formatted string into a US-ASCII {@link HttpData}. The string is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     *
     * @param format {@linkplain Formatter the format string} of the response content
     * @param args the arguments referenced by the format specifiers in the format string
     *
     * @return a new {@link HttpData}. {@link #EMPTY_DATA} if {@code format} is empty.
     */
    static HttpData ofAscii(String format, Object... args) {
        return of(StandardCharsets.US_ASCII, format, args);
    }

    /**
     * Returns the underlying byte array of this data.
     */
    byte[] array();

    /**
     * Returns the start offset of the {@link #array()}.
     */
    int offset();

    /**
     * Returns the length of this data.
     */
    int length();

    /**
     * Returns whether the {@link #length()} is 0.
     */
    default boolean isEmpty() {
        return length() == 0;
    }

    /**
     * Decodes this data into a {@link String}.
     *
     * @param charset the {@link Charset} of this data
     *
     * @return the decoded {@link String}
     */
    default String toString(Charset charset) {
        requireNonNull(charset, "charset");
        return new String(array(), offset(), length(), charset);
    }

    /**
     * Decodes this data into a {@link String} using UTF-8 encoding.
     *
     * @return the decoded {@link String}
     */
    default String toStringUtf8() {
        return toString(StandardCharsets.UTF_8);
    }

    /**
     * Decodes this data into a {@link String} using US-ASCII encoding.
     *
     * @return the decoded {@link String}
     */
    default String toStringAscii() {
        return toString(StandardCharsets.US_ASCII);
    }
}

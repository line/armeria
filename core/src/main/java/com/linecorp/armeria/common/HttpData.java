/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Formatter;
import java.util.Locale;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.linecorp.armeria.common.unsafe.PooledHttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import it.unimi.dsi.fastutil.io.FastByteArrayInputStream;

/**
 * HTTP/2 data.
 *
 * <p>Implementations should generally extend {@link AbstractHttpData} to interact with other {@link HttpData}
 * implementations.
 */
public interface HttpData extends HttpObject {

    /**
     * Returns an empty {@link HttpData}.
     */
    static HttpData empty() {
        return DefaultHttpData.EMPTY;
    }

    /**
     * Creates a new instance from the specified byte array. The array is not copied; any changes made in the
     * array later will be visible to {@link HttpData}.
     *
     * @return a new {@link HttpData}. {@link #empty()} if the length of the specified array is 0.
     */
    static HttpData wrap(byte[] data) {
        requireNonNull(data, "data");
        if (data.length == 0) {
            return empty();
        }

        return new DefaultHttpData(data, false);
    }

    /**
     * Creates a new instance from the specified byte array, {@code offset} and {@code length}.
     * The array is not copied; any changes made in the array later will be visible to {@link HttpData}.
     *
     * @return a new {@link HttpData}. {@link #empty()} if {@code length} is 0.
     *
     * @throws ArrayIndexOutOfBoundsException if {@code offset} and {@code length} are out of bounds
     */
    static HttpData wrap(byte[] data, int offset, int length) {
        requireNonNull(data, "data");
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new ArrayIndexOutOfBoundsException(
                    "offset: " + offset + ", length: " + length + ", data.length: " + data.length);
        }
        if (length == 0) {
            return empty();
        }

        if (data.length == length) {
            return wrap(data);
        }

        return new ByteRangeHttpData(data, offset, length, false);
    }

    /**
     * Converts the specified Netty {@link ByteBuf} into an {@link HttpData}. The buffer is not copied; any
     * changes made to it will be visible to {@link HttpData}. The ownership of the buffer is transferred to the
     * {@link HttpData}. If you still need to use it after calling this method, make sure to call
     * {@link ByteBuf#retain()} first.
     *
     * @return a new {@link HttpData}. {@link #empty()} if the readable bytes of {@code buf} is 0.
     *
     * @deprecated Use {@link PooledHttpData#wrap(ByteBuf)}.
     */
    @Deprecated
    static HttpData wrap(ByteBuf buf) {
        return PooledHttpData.wrap(buf);
    }

    /**
     * Creates a new instance from the specified byte array by first copying it.
     *
     * @return a new {@link HttpData}. {@link #empty()} if the length of the specified array is 0.
     */
    static HttpData copyOf(byte[] data) {
        requireNonNull(data, "data");
        if (data.length == 0) {
            return empty();
        }

        return new DefaultHttpData(data.clone(), false);
    }

    /**
     * Creates a new instance from the specified byte array, {@code offset} and {@code length} by first copying
     * it.
     *
     * @return a new {@link HttpData}. {@link #empty()} if {@code length} is 0.
     *
     * @throws ArrayIndexOutOfBoundsException if {@code offset} and {@code length} are out of bounds
     */
    static HttpData copyOf(byte[] data, int offset, int length) {
        requireNonNull(data);
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new ArrayIndexOutOfBoundsException(
                    "offset: " + offset + ", length: " + length + ", data.length: " + data.length);
        }
        if (length == 0) {
            return empty();
        }

        return new DefaultHttpData(Arrays.copyOfRange(data, offset, offset + length), false);
    }

    /**
     * Creates a new instance from the specified {@link ByteBuf} by first copying it's content. The reference
     * count of {@link ByteBuf} will not be changed.
     *
     * @return a new {@link HttpData}. {@link #empty()} if the length of the specified array is 0.
     */
    static HttpData copyOf(ByteBuf data) {
        requireNonNull(data, "data");
        if (!data.isReadable()) {
            return empty();
        }

        return wrap(ByteBufUtil.getBytes(data));
    }

    /**
     * Converts the specified {@code text} into an {@link HttpData}.
     *
     * @param charset the {@link Charset} to use for encoding {@code text}
     * @param text the {@link String} to convert
     *
     * @return a new {@link HttpData}. {@link #empty()} if the length of {@code text} is 0.
     */
    static HttpData of(Charset charset, CharSequence text) {
        requireNonNull(charset, "charset");
        requireNonNull(text, "text");

        if (text instanceof String) {
            return of(charset, (String) text);
        }

        if (text.length() == 0) {
            return empty();
        }

        final CharBuffer cb = CharBuffer.wrap(text);
        final ByteBuffer buf = charset.encode(cb);
        if (buf.arrayOffset() == 0 && buf.remaining() == buf.array().length) {
            return wrap(buf.array());
        } else {
            return copyOf(buf.array(), buf.arrayOffset(), buf.remaining());
        }
    }

    /**
     * Converts the specified {@code text} into an {@link HttpData}.
     *
     * @param charset the {@link Charset} to use for encoding {@code text}
     * @param text the {@link String} to convert
     *
     * @return a new {@link HttpData}. {@link #empty()} if the length of {@code text} is 0.
     */
    static HttpData of(Charset charset, String text) {
        requireNonNull(charset, "charset");
        requireNonNull(text, "text");
        if (text.isEmpty()) {
            return empty();
        }

        return wrap(text.getBytes(charset));
    }

    /**
     * Converts the specified formatted string into an {@link HttpData}. The string is formatted by
     * {@link String#format(Locale, String, Object...)} with {@linkplain Locale#ENGLISH English locale}.
     *
     * @param charset the {@link Charset} to use for encoding string
     * @param format {@linkplain Formatter the format string} of the response content
     * @param args the arguments referenced by the format specifiers in the format string
     *
     * @return a new {@link HttpData}. {@link #empty()} if {@code format} is empty.
     */
    @FormatMethod
    static HttpData of(Charset charset, @FormatString String format, Object... args) {
        requireNonNull(charset, "charset");
        requireNonNull(format, "format");
        requireNonNull(args, "args");

        if (format.isEmpty()) {
            return empty();
        }

        return wrap(String.format(Locale.ENGLISH, format, args).getBytes(charset));
    }

    /**
     * Converts the specified {@code text} into a UTF-8 {@link HttpData}.
     *
     * @param text the {@link String} to convert
     *
     * @return a new {@link HttpData}. {@link #empty()} if the length of {@code text} is 0.
     */
    static HttpData ofUtf8(CharSequence text) {
        return of(StandardCharsets.UTF_8, text);
    }

    /**
     * Converts the specified {@code text} into a UTF-8 {@link HttpData}.
     *
     * @param text the {@link String} to convert
     *
     * @return a new {@link HttpData}. {@link #empty()} if the length of {@code text} is 0.
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
     * @return a new {@link HttpData}. {@link #empty()} if {@code format} is empty.
     */
    @FormatMethod
    static HttpData ofUtf8(@FormatString String format, Object... args) {
        return of(StandardCharsets.UTF_8, format, args);
    }

    /**
     * Converts the specified {@code text} into a US-ASCII {@link HttpData}.
     *
     * @param text the {@link String} to convert
     *
     * @return a new {@link HttpData}. {@link #empty()} if the length of {@code text} is 0.
     */
    static HttpData ofAscii(CharSequence text) {
        return of(StandardCharsets.US_ASCII, text);
    }

    /**
     * Converts the specified {@code text} into a US-ASCII {@link HttpData}.
     *
     * @param text the {@link String} to convert
     *
     * @return a new {@link HttpData}. {@link #empty()} if the length of {@code text} is 0.
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
     * @return a new {@link HttpData}. {@link #empty()} if {@code format} is empty.
     */
    @FormatMethod
    static HttpData ofAscii(@FormatString String format, Object... args) {
        return of(StandardCharsets.US_ASCII, format, args);
    }

    /**
     * Returns the underlying byte array of this data.
     */
    byte[] array();

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
     * @param charset the {@link Charset} to use for decoding this data
     *
     * @return the decoded {@link String}
     */
    default String toString(Charset charset) {
        requireNonNull(charset, "charset");
        return new String(array(), charset);
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

    /**
     * Returns a new {@link InputStream} that is sourced from this data.
     */
    default InputStream toInputStream() {
        return new FastByteArrayInputStream(array());
    }

    /**
     * Returns a new {@link Reader} that is sourced from this data and decoded using the specified
     * {@link Charset}.
     */
    default Reader toReader(Charset charset) {
        requireNonNull(charset, "charset");
        return new InputStreamReader(toInputStream(), charset);
    }

    /**
     * Returns a new {@link Reader} that is sourced from this data and decoded using
     * {@link StandardCharsets#UTF_8}.
     */
    default Reader toReaderUtf8() {
        return toReader(StandardCharsets.UTF_8);
    }

    /**
     * Returns a new {@link Reader} that is sourced from this data and decoded using
     * {@link StandardCharsets#US_ASCII}.
     */
    default Reader toReaderAscii() {
        return toReader(StandardCharsets.US_ASCII);
    }

    /**
     * Returns a new {@link HttpData} whose HTTP/2 {@code endOfStream} flag is set.
     */
    default HttpData withEndOfStream() {
        return withEndOfStream(true);
    }

    /**
     * Returns a new {@link HttpData} whose HTTP/2 {@code endOfStream} flag is set to {@code endOfStream}.
     */
    HttpData withEndOfStream(boolean endOfStream);
}

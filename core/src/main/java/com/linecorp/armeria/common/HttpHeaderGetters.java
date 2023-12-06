/*
 * Copyright 2019 LINE Corporation
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

import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.google.common.collect.Streams;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AsciiString;

/**
 * Provides the getter methods to {@link HttpHeaders} and {@link HttpHeadersBuilder}.
 */
interface HttpHeaderGetters extends StringMultimapGetters</* IN_NAME */ CharSequence, /* NAME */ AsciiString> {

    /**
     * Tells whether the headers correspond to the last frame in an HTTP/2 stream.
     */
    boolean isEndOfStream();

    /**
     * Returns the value of the
     * <a href="https://datatracker.ietf.org/doc/html/rfc2616#section-14.13">content-length</a> header,
     * or {@code -1} if this value is not known.
     */
    long contentLength();

    /**
     * Returns whether the content length is unknown.
     * If {@code true}, {@code content-length} header is not automatically updated.
     */
    @UnstableApi
    boolean isContentLengthUnknown();

    /**
     * Returns the parsed {@code "content-type"} header.
     *
     * @return the parsed {@link MediaType} if present and valid, or {@code null} otherwise.
     */
    @Nullable
    MediaType contentType();

    /**
     * Returns the parsed {@code "content-disposition"} header.
     *
     * @return the parsed {@link MediaType} if present and valid. {@code null} if not present or
     *         failed to parse {@code "content-disposition"} header.
     */
    @Nullable
    ContentDisposition contentDisposition();

    /**
     * Returns the value of a header with the specified {@code name}. If there are more than one value for
     * the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the first header value if the header is found, or {@code null} if there's no such header
     */
    @Override
    @Nullable
    String get(CharSequence name);

    /**
     * Returns the value of a header with the specified {@code name}. If there are more than one value for
     * the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @param defaultValue the default value
     * @return the first header value or {@code defaultValue} if there is no such header
     */
    @Override
    String get(CharSequence name, String defaultValue);

    /**
     * Returns the value of a header with the specified {@code name}. If there are more than one value for
     * the specified {@code name}, the last value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the last header value if the header is found, or {@code null} if there's no such header
     */
    @Override
    @Nullable
    String getLast(CharSequence name);

    /**
     * Returns the value of a header with the specified {@code name}. If there are more than one value for
     * the specified {@code name}, the last value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @param defaultValue the default value
     * @return the last header value or {@code defaultValue} if there is no such header
     */
    @Override
    String getLast(CharSequence name, String defaultValue);

    /**
     * Returns all values for the header with the specified name. The returned {@link List} can't be modified.
     *
     * @param name the name of the header to retrieve
     * @return a {@link List} of header values or an empty {@link List} if there is no such header.
     */
    @Override
    List<String> getAll(CharSequence name);

    /**
     * Returns the {@code boolean} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the {@code boolean} value of the first value in insertion order or {@code null} if there is
     *         no such header or it can't be converted to {@code boolean}.
     */
    @Override
    @Nullable
    Boolean getBoolean(CharSequence name);

    /**
     * Returns the {@code boolean} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @param defaultValue the default value
     * @return the {@code boolean} value of the first value in insertion order or {@code defaultValue}
     *         if there is no such header or it can't be converted to {@code boolean}.
     */
    @Override
    boolean getBoolean(CharSequence name, boolean defaultValue);

    /**
     * Returns the {@code boolean} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the last value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the {@code boolean} value of the last value in insertion order or {@code null} if there is
     *         no such header or it can't be converted to {@code boolean}.
     */
    @Override
    @Nullable
    Boolean getLastBoolean(CharSequence name);

    /**
     * Returns the {@code boolean} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the last value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @param defaultValue the default value
     * @return the {@code boolean} value of the last value in insertion order or {@code defaultValue}
     *         if there is no such header or it can't be converted to {@code boolean}.
     */
    @Override
    boolean getLastBoolean(CharSequence name, boolean defaultValue);

    /**
     * Returns the {@code int} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the last value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the {@code int} value of the last value in insertion order or {@code null} if there is no such
     *         header or it can't be converted to {@code int}.
     */
    @Override
    @Nullable
    Integer getLastInt(CharSequence name);

    /**
     * Returns the {@code int} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the last value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @param defaultValue the default value
     * @return the {@code int} value of the last value in insertion order or {@code defaultValue} if there is
     *         no such header or it can't be converted to {@code int}.
     */
    @Override
    int getLastInt(CharSequence name, int defaultValue);

    /**
     * Returns the {@code int} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the {@code int} value of the first value in insertion order or {@code null} if there is no such
     *         header or it can't be converted to {@code int}.
     */
    @Override
    @Nullable
    Integer getInt(CharSequence name);

    /**
     * Returns the {@code int} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @param defaultValue the default value
     * @return the {@code int} value of the first value in insertion order or {@code defaultValue} if there is
     *         no such header or it can't be converted to {@code int}.
     */
    @Override
    int getInt(CharSequence name, int defaultValue);

    /**
     * Returns the {@code long} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the {@code long} value of the first value in insertion order or {@code null} if there is no such
     *         header or it can't be converted to {@code long}.
     */
    @Override
    @Nullable
    Long getLong(CharSequence name);

    /**
     * Returns the {@code long} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @param defaultValue the default value
     * @return the {@code long} value of the first value in insertion order or {@code defaultValue} if there is
     *         no such header or it can't be converted to {@code long}.
     */
    @Override
    long getLong(CharSequence name, long defaultValue);

    /**
     * Returns the {@code long} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the last value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the {@code long} value of the last value in insertion order or {@code null} if there is no such
     *         header or it can't be converted to {@code long}.
     */
    @Override
    @Nullable
    Long getLastLong(CharSequence name);

    /**
     * Returns the {@code long} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the last value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @param defaultValue the default value
     * @return the {@code long} value of the last value in insertion order or {@code defaultValue} if there is
     *         no such header or it can't be converted to {@code long}.
     */
    @Override
    long getLastLong(CharSequence name, long defaultValue);

    /**
     * Returns the {@code float} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the {@code float} value of the first value in insertion order or {@code null} if there is no
     *         such header or it can't be converted to {@code float}.
     */
    @Override
    @Nullable
    Float getFloat(CharSequence name);

    /**
     * Returns the {@code float} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @param defaultValue the default value
     * @return the {@code float} value of the first value in insertion order or {@code defaultValue} if there
     *         is no such header or it can't be converted to {@code float}.
     */
    @Override
    float getFloat(CharSequence name, float defaultValue);

    /**
     * Returns the {@code float} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the last value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the {@code float} value of the last value in insertion order or {@code null} if there is no
     *         such header or it can't be converted to {@code float}.
     */
    @Override
    @Nullable
    Float getLastFloat(CharSequence name);

    /**
     * Returns the {@code float} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the last value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @param defaultValue the default value
     * @return the {@code float} value of the last value in insertion order or {@code defaultValue} if there
     *         is no such header or it can't be converted to {@code float}.
     */
    @Override
    float getLastFloat(CharSequence name, float defaultValue);

    /**
     * Returns the {@code double} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the {@code double} value of the first value in insertion order or {@code null} if there is no
     *         such header or it can't be converted to {@code double}.
     */
    @Override
    @Nullable
    Double getDouble(CharSequence name);

    /**
     * Returns the {@code double} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @param defaultValue the default value
     * @return the {@code double} value of the first value in insertion order or {@code defaultValue} if there
     *         is no such header or it can't be converted to {@code double}.
     */
    @Override
    double getDouble(CharSequence name, double defaultValue);

    /**
     * Returns the {@code double} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the last value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the {@code double} value of the last value in insertion order or {@code null} if there is no
     *         such header or it can't be converted to {@code double}.
     */
    @Override
    @Nullable
    Double getLastDouble(CharSequence name);

    /**
     * Returns the {@code double} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the last value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @param defaultValue the default value
     * @return the {@code double} value of the last value in insertion order or {@code defaultValue} if there
     *         is no such header or it can't be converted to {@code double}.
     */
    @Override
    double getLastDouble(CharSequence name, double defaultValue);

    /**
     * Returns the value of a header with the specified {@code name} in milliseconds. If there are more than
     * one value for the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the milliseconds value of the first value in insertion order or {@code null} if there is no such
     *         header or it can't be converted to milliseconds.
     */
    @Override
    @Nullable
    Long getTimeMillis(CharSequence name);

    /**
     * Returns the value of a header with the specified {@code name} in milliseconds. If there are more than
     * one value for the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @param defaultValue the default value
     * @return the milliseconds value of the first value in insertion order or {@code defaultValue} if there is
     *         no such header or it can't be converted to milliseconds.
     */
    @Override
    long getTimeMillis(CharSequence name, long defaultValue);

    /**
     * Returns the value of a header with the specified {@code name} in milliseconds. If there are more than
     * one value for the specified {@code name}, the last value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the milliseconds value of the last value in insertion order or {@code null} if there is no such
     *         header or it can't be converted to milliseconds.
     */
    @Override
    @Nullable
    Long getLastTimeMillis(CharSequence name);

    /**
     * Returns the value of a header with the specified {@code name} in milliseconds. If there are more than
     * one value for the specified {@code name}, the last value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @param defaultValue the default value
     * @return the milliseconds value of the last value in insertion order or {@code defaultValue} if there is
     *         no such header or it can't be converted to milliseconds.
     */
    @Override
    long getLastTimeMillis(CharSequence name, long defaultValue);

    /**
     * Returns {@code true} if a header with the {@code name} exists, {@code false} otherwise.
     *
     * @param name the header name
     */
    @Override
    boolean contains(CharSequence name);

    /**
     * Returns {@code true} if a header with the {@code name} and {@code value} exists.
     *
     * @param name the header name
     * @param value the header value of the header to find
     */
    @Override
    boolean contains(CharSequence name, String value);

    /**
     * Returns {@code true} if a header with the {@code name} and {@code value} exists.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code true} if the header exists. {@code false} otherwise
     */
    @Override
    boolean containsObject(CharSequence name, Object value);

    /**
     * Returns {@code true} if a header with the {@code name} and {@code value} exists.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code true} if the header exists. {@code false} otherwise
     */
    @Override
    boolean containsBoolean(CharSequence name, boolean value);

    /**
     * Returns {@code true} if a header with the {@code name} and {@code value} exists.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code true} if the header exists. {@code false} otherwise
     */
    @Override
    boolean containsInt(CharSequence name, int value);

    /**
     * Returns {@code true} if a header with the {@code name} and {@code value} exists.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code true} if the header exists. {@code false} otherwise
     */
    @Override
    boolean containsLong(CharSequence name, long value);

    /**
     * Returns {@code true} if a header with the {@code name} and {@code value} exists.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code true} if the header exists. {@code false} otherwise
     */
    @Override
    boolean containsFloat(CharSequence name, float value);

    /**
     * Returns {@code true} if a header with the {@code name} and {@code value} exists.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code true} if the header exists. {@code false} otherwise
     */
    @Override
    boolean containsDouble(CharSequence name, double value);

    /**
     * Returns {@code true} if a header with the {@code name} and {@code value} exists.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code true} if the header exists. {@code false} otherwise
     */
    @Override
    boolean containsTimeMillis(CharSequence name, long value);

    /**
     * Returns the number of headers.
     */
    @Override
    int size();

    /**
     * Returns {@code true} if this headers does not contain any entries.
     */
    @Override
    boolean isEmpty();

    /**
     * Returns a {@link Set} of all header names. The returned {@link Set} cannot be modified.
     */
    @Override
    Set<AsciiString> names();

    /**
     * Returns an {@link Iterator} that yields all header entries. The iteration order is as follows:
     * <ol>
     *   <li>All pseudo headers (order not specified).</li>
     *   <li>All non-pseudo headers (in insertion order).</li>
     * </ol>
     */
    @Override
    Iterator<Entry<AsciiString, String>> iterator();

    /**
     * Returns an {@link Iterator} that yields all values of the headers with the specified {@code name}.
     */
    @Override
    Iterator<String> valueIterator(CharSequence name);

    /**
     * Invokes the specified {@code action} for all header entries.
     */
    @Override
    void forEach(BiConsumer<AsciiString, String> action);

    /**
     * Invokes the specified {@code action} for all values of the headers with the specified {@code name}.
     */
    @Override
    void forEachValue(CharSequence name, Consumer<String> action);

    /**
     * Returns a {@link Stream} that yields all header entries.
     */
    @Override
    default Stream<Entry<AsciiString, String>> stream() {
        return Streams.stream(iterator());
    }

    /**
     * Returns a {@link Stream} that yields all values of the headers with the specified {@code name}.
     */
    @Override
    default Stream<String> valueStream(CharSequence name) {
        requireNonNull(name, "name");
        return Streams.stream(valueIterator(name));
    }
}

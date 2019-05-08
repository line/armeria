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

import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.google.common.collect.Streams;

import io.netty.util.AsciiString;

/**
 * Provides the getter methods to {@link HttpHeaders} and {@link HttpHeadersBuilder}.
 */
interface HttpHeaderGetters extends Iterable<Entry<AsciiString, String>> {

    /**
     * Tells whether the headers correspond to the last frame in an HTTP/2 stream.
     */
    boolean isEndOfStream();

    /**
     * Returns the parsed {@code "content-type"} header.
     *
     * @return the parsed {@link MediaType} if present and valid. {@code null} otherwise.
     */
    @Nullable
    MediaType contentType();

    /**
     * Returns the value of a header with the specified {@code name}. If there are more than one value for
     * the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the first header value if the header is found. {@code null} if there's no such header
     */
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
    String get(CharSequence name, String defaultValue);

    /**
     * Returns all values for the header with the specified name. The returned {@link List} can't be modified.
     *
     * @param name the name of the header to retrieve
     * @return a {@link List} of header values or an empty {@link List} if there is no such header.
     */
    List<String> getAll(CharSequence name);

    /**
     * Returns the {@code int} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the {@code int} value of the first value in insertion order or {@code null} if there is no such
     *         header or it can't be converted to {@code int}.
     */
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
    int getInt(CharSequence name, int defaultValue);

    /**
     * Returns the {@code long} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the {@code long} value of the first value in insertion order or {@code null} if there is no such
     *         header or it can't be converted to {@code long}.
     */
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
    long getLong(CharSequence name, long defaultValue);

    /**
     * Returns the {@code float} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the {@code float} value of the first value in insertion order or {@code null} if there is no
     *         such header or it can't be converted to {@code float}.
     */
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
    float getFloat(CharSequence name, float defaultValue);

    /**
     * Returns the {@code double} value of a header with the specified {@code name}. If there are more than one
     * value for the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the {@code double} value of the first value in insertion order or {@code null} if there is no
     *         such header or it can't be converted to {@code double}.
     */
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
    double getDouble(CharSequence name, double defaultValue);

    /**
     * Returns the value of a header with the specified {@code name} in milliseconds. If there are more than
     * one value for the specified {@code name}, the first value in insertion order is returned.
     *
     * @param name the name of the header to retrieve
     * @return the milliseconds value of the first value in insertion order or {@code null} if there is no such
     *         header or it can't be converted to milliseconds.
     */
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
    long getTimeMillis(CharSequence name, long defaultValue);

    /**
     * Returns {@code true} if a header with the {@code name} exists, {@code false} otherwise.
     *
     * @param name the header name
     */
    boolean contains(CharSequence name);

    /**
     * Returns {@code true} if a header with the {@code name} and {@code value} exists.
     *
     * @param name the header name
     * @param value the header value of the header to find
     */
    boolean contains(CharSequence name, String value);

    /**
     * Returns {@code true} if a header with the {@code name} and {@code value} exists.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code true} if the header exists. {@code false} otherwise
     */
    boolean containsObject(CharSequence name, Object value);

    /**
     * Returns {@code true} if a header with the {@code name} and {@code value} exists.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code true} if the header exists. {@code false} otherwise
     */
    boolean containsInt(CharSequence name, int value);

    /**
     * Returns {@code true} if a header with the {@code name} and {@code value} exists.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code true} if the header exists. {@code false} otherwise
     */
    boolean containsLong(CharSequence name, long value);

    /**
     * Returns {@code true} if a header with the {@code name} and {@code value} exists.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code true} if the header exists. {@code false} otherwise
     */
    boolean containsFloat(CharSequence name, float value);

    /**
     * Returns {@code true} if a header with the {@code name} and {@code value} exists.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code true} if the header exists. {@code false} otherwise
     */
    boolean containsDouble(CharSequence name, double value);

    /**
     * Returns {@code true} if a header with the {@code name} and {@code value} exists.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code true} if the header exists. {@code false} otherwise
     */
    boolean containsTimeMillis(CharSequence name, long value);

    /**
     * Returns the number of headers.
     */
    int size();

    /**
     * Returns {@code true} if this headers does not contain any entries.
     */
    boolean isEmpty();

    /**
     * Returns a {@link Set} of all header names. The returned {@link Set} cannot be modified.
     */
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
    Iterator<String> valueIterator(CharSequence name);

    /**
     * Invokes the specified {@code action} for all header entries.
     */
    void forEach(BiConsumer<AsciiString, String> action);

    /**
     * Invokes the specified {@code action} for all values of the headers with the specified {@code name}.
     */
    void forEachValue(CharSequence name, Consumer<String> action);

    /**
     * Returns a {@link Stream} that yields all header entries.
     */
    default Stream<Entry<AsciiString, String>> stream() {
        return Streams.stream(iterator());
    }

    /**
     * Returns a {@link Stream} that yields all values of the headers with the specified {@code name}.
     */
    default Stream<String> valueStream(CharSequence name) {
        return Streams.stream(valueIterator(name));
    }
}

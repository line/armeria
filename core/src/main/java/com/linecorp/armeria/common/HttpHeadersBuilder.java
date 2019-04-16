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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import io.netty.util.AsciiString;

/**
 * Builds an {@link HttpHeaders}.
 *
 * @see RequestHeadersBuilder
 * @see ResponseHeadersBuilder
 */
public class HttpHeadersBuilder implements HttpHeaderGetters {

    private int sizeHint = HttpHeadersBase.DEFAULT_SIZE_HINT;
    @Nullable
    private HttpHeadersBase delegate;
    @Nullable
    private HttpHeadersBase parent;

    HttpHeadersBuilder() {}

    HttpHeadersBuilder(HttpHeadersBase parent) {
        assert parent instanceof HttpHeaders;
        this.parent = parent;
    }

    /**
     * Returns a newly created {@link HttpHeaders} with the entries in this builder.
     * Note that any further modification of this builder is prohibited after this method is invoked.
     */
    public HttpHeaders build() {
        if (delegate != null) {
            if (delegate.isEmpty()) {
                return delegate.isEndOfStream() ? DefaultHttpHeaders.EMPTY_EOS : DefaultHttpHeaders.EMPTY;
            } else {
                return new DefaultHttpHeaders(promoteDelegate());
            }
        }

        return parent != null ? (HttpHeaders) parent : DefaultHttpHeaders.EMPTY;
    }

    @Nullable
    final HttpHeadersBase delegate() {
        return delegate;
    }

    @Nullable
    final HttpHeadersBase parent() {
        return parent;
    }

    /**
     * Makes the current {@link #delegate()} a new {@link #parent()} and clears the current {@link #delegate()}.
     * Call this method when you create a new {@link HttpHeaders} derived from the {@link #delegate()},
     * so that any further modifications to this builder do not break the immutability of {@link HttpHeaders}.
     *
     * @return the {@link #delegate()}
     */
    final HttpHeadersBase promoteDelegate() {
        final HttpHeadersBase delegate = this.delegate;
        assert delegate != null;
        parent = delegate;
        this.delegate = null;
        return delegate;
    }

    @Nullable
    final HttpHeadersBase getters() {
        if (delegate != null) {
            return delegate;
        }

        if (parent != null) {
            return parent;
        }

        return null;
    }

    final HttpHeadersBase setters() {
        if (delegate != null) {
            return delegate;
        }

        if (parent != null) {
            // Make a deep copy of the parent.
            return delegate = new HttpHeadersBase(parent, false);
        }

        return delegate = new HttpHeadersBase(sizeHint);
    }

    /**
     * Specifies the hint about the number of headers which may improve the memory efficiency and performance
     * of the underlying data structure.
     *
     * @return {@code this}
     * @throws IllegalStateException if the hint was specified too late after the underlying data structure
     *                               has been fully initialized.
     */
    public HttpHeadersBuilder sizeHint(int sizeHint) {
        checkArgument(sizeHint >= 0, "sizeHint: %s (expected: >= 0)", sizeHint);
        checkState(delegate == null, "sizeHint cannot be specified after a modification is made.");
        this.sizeHint = sizeHint;
        return this;
    }

    // Shortcuts

    @Override
    @Nullable
    public final MediaType contentType() {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.contentType() : null;
    }

    /**
     * Sets the {@code "content-type"} header.
     */
    public HttpHeadersBuilder contentType(MediaType contentType) {
        setters().contentType(contentType);
        return this;
    }

    // Getters

    @Override
    public final boolean isEndOfStream() {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.isEndOfStream() : false;
    }

    @Override
    @Nullable
    public final String get(CharSequence name) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.get(name) : null;
    }

    @Override
    public final String get(CharSequence name, String defaultValue) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.get(name, defaultValue)
                               : requireNonNull(defaultValue, "defaultValue");
    }

    @Override
    public final List<String> getAll(CharSequence name) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.getAll(name) : ImmutableList.of();
    }

    @Override
    @Nullable
    public final Integer getInt(CharSequence name) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.getInt(name) : null;
    }

    @Override
    public final int getInt(CharSequence name, int defaultValue) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.getInt(name, defaultValue) : defaultValue;
    }

    @Override
    @Nullable
    public final Long getLong(CharSequence name) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.getLong(name) : null;
    }

    @Override
    public final long getLong(CharSequence name, long defaultValue) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.getLong(name, defaultValue) : defaultValue;
    }

    @Override
    @Nullable
    public final Float getFloat(CharSequence name) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.getFloat(name) : null;
    }

    @Override
    public final float getFloat(CharSequence name, float defaultValue) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.getFloat(name, defaultValue) : defaultValue;
    }

    @Override
    @Nullable
    public final Double getDouble(CharSequence name) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.getDouble(name) : null;
    }

    @Override
    public final double getDouble(CharSequence name, double defaultValue) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.getDouble(name, defaultValue) : defaultValue;
    }

    @Override
    @Nullable
    public final Long getTimeMillis(CharSequence name) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.getTimeMillis(name) : null;
    }

    @Override
    public final long getTimeMillis(CharSequence name, long defaultValue) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.getTimeMillis(name, defaultValue) : defaultValue;
    }

    @Override
    public final boolean contains(CharSequence name) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.contains(name) : false;
    }

    @Override
    public final boolean contains(CharSequence name, String value) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.contains(name, value) : false;
    }

    @Override
    public final boolean containsObject(CharSequence name, Object value) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.containsObject(name, value) : false;
    }

    @Override
    public final boolean containsInt(CharSequence name, int value) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.containsInt(name, value) : false;
    }

    @Override
    public final boolean containsLong(CharSequence name, long value) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.containsLong(name, value) : false;
    }

    @Override
    public final boolean containsFloat(CharSequence name, float value) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.containsFloat(name, value) : false;
    }

    @Override
    public final boolean containsDouble(CharSequence name, double value) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.containsDouble(name, value) : false;
    }

    @Override
    public final boolean containsTimeMillis(CharSequence name, long value) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.containsTimeMillis(name, value) : false;
    }

    @Override
    public final int size() {
        if (delegate != null) {
            return delegate.size();
        }
        if (parent != null) {
            return parent.size();
        }
        return 0;
    }

    @Override
    public final boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public final Set<AsciiString> names() {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.names() : ImmutableSet.of();
    }

    @Override
    public final Iterator<Entry<AsciiString, String>> iterator() {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.iterator() : Collections.emptyIterator();
    }

    @Override
    public final Iterator<String> valueIterator(CharSequence name) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.valueIterator(name) : Collections.emptyIterator();
    }

    @Override
    public final void forEach(BiConsumer<AsciiString, String> action) {
        final HttpHeadersBase getters = getters();
        if (getters != null) {
            getters.forEach(action);
        }
    }

    @Override
    public final void forEachValue(CharSequence name, Consumer<String> action) {
        final HttpHeadersBase getters = getters();
        if (getters != null) {
            getters.forEachValue(name, action);
        }
    }

    @Override
    public final Stream<Entry<AsciiString, String>> stream() {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.stream() : Stream.empty();
    }

    @Override
    public final Stream<String> valueStream(CharSequence name) {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.valueStream(name) : Stream.empty();
    }

    // Setters

    /**
     * Sets whether the headers will be the last frame in an HTTP/2 stream.
     */
    public HttpHeadersBuilder endOfStream(boolean endOfStream) {
        setters().endOfStream(endOfStream);
        return this;
    }

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the name of the header to retrieve
     * @return the first header value or {@code null} if there is no such header
     */
    @Nullable
    public final String getAndRemove(CharSequence name) {
        return contains(name) ? setters().getAndRemove(name) : null;
    }

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the name of the header to retrieve
     * @param defaultValue the default value
     * @return the first header value or {@code defaultValue} if there is no such header
     */
    public final String getAndRemove(CharSequence name, String defaultValue) {
        return contains(name) ? setters().getAndRemove(name, defaultValue)
                              : requireNonNull(defaultValue, "defaultValue");
    }

    /**
     * Removes all the headers with the specified name and returns the removed header values.
     *
     * @param name the name of the header to retrieve
     * @return a {@link List} of header values or an empty {@link List} if no values are found.
     */
    public final List<String> getAllAndRemove(CharSequence name) {
        return contains(name) ? setters().getAllAndRemove(name) : ImmutableList.of();
    }

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the name of the header to search
     * @return the {@code int} value of the first value in insertion order or {@code null} if there is no
     *         such value or it can't be converted to {@code int}.
     */
    @Nullable
    public final Integer getIntAndRemove(CharSequence name) {
        return contains(name) ? setters().getIntAndRemove(name) : null;
    }

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the name of the header to search
     * @param defaultValue the default value
     * @return the {@code int} value of the first value in insertion order or {@code defaultValue} if there is
     *         no such value or it can't be converted to {@code int}.
     */
    public final int getIntAndRemove(CharSequence name, int defaultValue) {
        return contains(name) ? setters().getIntAndRemove(name, defaultValue) : defaultValue;
    }

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the name of the header to search
     * @return the {@code long} value of the first value in insertion order or {@code null} if there is no such
     *         value or it can't be converted to {@code long}.
     */
    @Nullable
    public final Long getLongAndRemove(CharSequence name) {
        return contains(name) ? setters().getLongAndRemove(name) : null;
    }

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the name of the header to search
     * @param defaultValue the default value
     * @return the {@code long} value of the first value in insertion order or {@code defaultValue} if there is
     *         no such value or it can't be converted to {@code long}.
     */
    public final long getLongAndRemove(CharSequence name, long defaultValue) {
        return contains(name) ? setters().getLongAndRemove(name, defaultValue) : defaultValue;
    }

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the name of the header to search
     * @return the {@code float} value of the first value in insertion order or {@code null} if there is
     *         no such value or it can't be converted to {@code float}.
     */
    @Nullable
    public final Float getFloatAndRemove(CharSequence name) {
        return contains(name) ? setters().getFloatAndRemove(name) : null;
    }

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the name of the header to search
     * @param defaultValue the default value
     * @return the {@code float} value of the first value in insertion order or {@code defaultValue} if there
     *         is no such value or it can't be converted to {@code float}.
     */
    public final float getFloatAndRemove(CharSequence name, float defaultValue) {
        return contains(name) ? setters().getFloatAndRemove(name, defaultValue) : defaultValue;
    }

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the name of the header to search
     * @return the {@code double} value of the first value in insertion order or {@code null} if there is
     *         no such value or it can't be converted to {@code double}.
     */
    @Nullable
    public final Double getDoubleAndRemove(CharSequence name) {
        return contains(name) ? setters().getDoubleAndRemove(name) : null;
    }

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the name of the header to search
     * @param defaultValue the default value
     * @return the {@code double} value of the first value in insertion order or {@code defaultValue} if there
     *         is no such value or it can't be converted to {@code double}.
     */
    public final double getDoubleAndRemove(CharSequence name, double defaultValue) {
        return contains(name) ? setters().getDoubleAndRemove(name, defaultValue) : defaultValue;
    }

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the name of the header to retrieve
     * @return the milliseconds value of the first value in insertion order or {@code null} if there is no such
     *         value or it can't be converted to milliseconds.
     */
    @Nullable
    public final Long getTimeMillisAndRemove(CharSequence name) {
        return contains(name) ? setters().getTimeMillisAndRemove(name) : null;
    }

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the name of the header to retrieve
     * @param defaultValue the default value
     * @return the milliseconds value of the first value in insertion order or {@code defaultValue} if there is
     *         no such value or it can't be converted to milliseconds.
     */
    public final long getTimeMillisAndRemove(CharSequence name, long defaultValue) {
        return contains(name) ? setters().getTimeMillisAndRemove(name, defaultValue) : defaultValue;
    }

    /**
     * Adds a new header with the specified {@code name} and {@code value}.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    public HttpHeadersBuilder add(CharSequence name, String value) {
        setters().add(name, value);
        return this;
    }

    /**
     * Adds new headers with the specified {@code name} and {@code values}. This method is semantically
     * equivalent to
     * <pre>{@code
     * for (String value : values) {
     *     headers.add(name, value);
     * }
     * }</pre>
     *
     * @param name the header name
     * @param values the header values
     * @return {@code this}
     */
    public HttpHeadersBuilder add(CharSequence name, Iterable<String> values) {
        setters().add(name, values);
        return this;
    }

    /**
     * Adds new headers with the specified {@code name} and {@code values}. This method is semantically
     * equivalent to
     * <pre>{@code
     * for (String value : values) {
     *     headers.add(name, value);
     * }
     * }</pre>
     *
     * @param name the header name
     * @param values the header values
     * @return {@code this}
     */
    public HttpHeadersBuilder add(CharSequence name, String... values) {
        setters().add(name, values);
        return this;
    }

    /**
     * Adds all header names and values of the specified {@code headers}.
     *
     * @return {@code this}
     * @throws IllegalArgumentException if {@code headers == this}.
     */
    public HttpHeadersBuilder add(
            Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        setters().add(headers);
        return this;
    }

    /**
     * Adds a new header. Before the {@code value} is added, it's converted to a {@link String}.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    public HttpHeadersBuilder addObject(CharSequence name, Object value) {
        setters().addObject(name, value);
        return this;
    }

    /**
     * Adds a new header with the specified name and values. This method is equivalent to
     * <pre>{@code
     * for (Object v : values) {
     *     headers.addObject(name, v);
     * }
     * }</pre>
     *
     * @param name the header name
     * @param values the header values
     * @return {@code this}
     */
    public HttpHeadersBuilder addObject(CharSequence name, Iterable<?> values) {
        setters().addObject(name, values);
        return this;
    }

    /**
     * Adds a new header with the specified name and values. This method is equivalent to
     * <pre>{@code
     * for (Object v : values) {
     *     headers.addObject(name, v);
     * }
     * }</pre>
     *
     * @param name the header name
     * @param values the header values
     * @return {@code this}
     */
    public HttpHeadersBuilder addObject(CharSequence name, Object... values) {
        setters().addObject(name, values);
        return this;
    }

    /**
     * Adds all header names and values of the specified {@code headers}.
     *
     * @return {@code this}
     * @throws IllegalArgumentException if {@code headers == this}.
     */
    public HttpHeadersBuilder addObject(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        setters().addObject(headers);
        return this;
    }

    /**
     * Adds a new header.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    public HttpHeadersBuilder addInt(CharSequence name, int value) {
        setters().addInt(name, value);
        return this;
    }

    /**
     * Adds a new header.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    public HttpHeadersBuilder addLong(CharSequence name, long value) {
        setters().addLong(name, value);
        return this;
    }

    /**
     * Adds a new header.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    public HttpHeadersBuilder addFloat(CharSequence name, float value) {
        setters().addFloat(name, value);
        return this;
    }

    /**
     * Adds a new header.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    public HttpHeadersBuilder addDouble(CharSequence name, double value) {
        setters().addDouble(name, value);
        return this;
    }

    /**
     * Adds a new header.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    public HttpHeadersBuilder addTimeMillis(CharSequence name, long value) {
        setters().addTimeMillis(name, value);
        return this;
    }

    /**
     * Sets a header with the specified name and value. Any existing headers with the same name are
     * overwritten.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    public HttpHeadersBuilder set(CharSequence name, String value) {
        setters().set(name, value);
        return this;
    }

    /**
     * Sets a new header with the specified name and values. This method is equivalent to
     * <pre>{@code
     * headers.remove(name);
     * for (String v : values) {
     *     headers.add(name, v);
     * }
     * }</pre>
     *
     * @param name the header name
     * @param values the header values
     * @return {@code this}
     */
    public HttpHeadersBuilder set(CharSequence name, Iterable<String> values) {
        setters().set(name, values);
        return this;
    }

    /**
     * Sets a header with the specified name and values. Any existing headers with the specified name are
     * removed. This method is equivalent to:
     * <pre>{@code
     * headers.remove(name);
     * for (String v : values) {
     *     headers.add(name, v);
     * }
     * }</pre>
     *
     * @param name the header name
     * @param values the header values
     * @return {@code this}
     */
    public HttpHeadersBuilder set(CharSequence name, String... values) {
        setters().set(name, values);
        return this;
    }

    /**
     * Retains all current headers but calls {@link #set(CharSequence, String)} for each entry in
     * the specified {@code headers}.
     *
     * @param headers the headers used to set the header values
     * @return {@code this}
     */
    public HttpHeadersBuilder set(Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        setters().set(headers);
        return this;
    }

    /**
     * Copies the entries missing in this headers from the specified headers.
     * This method is a shortcut of the following code:
     * <pre>{@code
     * headers.names().forEach(name -> {
     *      if (!contains(name)) {
     *          set(name, headers.getAll(name));
     *      }
     * });
     * }</pre>
     *
     * @return {@code this}
     */
    public HttpHeadersBuilder setIfAbsent(Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        setters().setIfAbsent(headers);
        return this;
    }

    /**
     * Sets a new header. Any existing headers with the specified name are removed. Before the {@code value} is
     * added, it's converted to a {@link String}.
     *
     * @param name the header name
     * @param value the value of the header
     * @return {@code this}
     */
    public HttpHeadersBuilder setObject(CharSequence name, Object value) {
        setters().setObject(name, value);
        return this;
    }

    /**
     * Sets a header with the specified name and values. Any existing headers with the specified name are
     * removed. This method is equivalent to:
     * <pre>{@code
     * headers.remove(name);
     * for (Object v : values) {
     *     headers.addObject(name, v);
     * }
     * }</pre>
     *
     * @param name the header name
     * @param values the values of the header
     * @return {@code this}
     */
    public HttpHeadersBuilder setObject(CharSequence name, Iterable<?> values) {
        setters().setObject(name, values);
        return this;
    }

    /**
     * Sets a header with the specified name and values. Any existing headers with the specified name are
     * removed. This method is equivalent to:
     * <pre>{@code
     * headers.remove(name);
     * for (Object v : values) {
     *     headers.addObject(name, v);
     * }
     * }</pre>
     *
     * @param name the header name
     * @param values the values of the header
     * @return {@code this}
     */
    public HttpHeadersBuilder setObject(CharSequence name, Object... values) {
        setters().setObject(name, values);
        return this;
    }

    /**
     * Retains all current headers but calls {@link #setObject(CharSequence, Object)} for each entry in
     * the specified {@code headers}.
     *
     * @param headers the headers used to set the values in this instance
     * @return {@code this}
     */
    public HttpHeadersBuilder setObject(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        setters().setObject(headers);
        return this;
    }

    /**
     * Sets a header with the specified {@code name} to {@code value}. This will remove all previous values
     * associated with {@code name}.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    public HttpHeadersBuilder setInt(CharSequence name, int value) {
        setters().setInt(name, value);
        return this;
    }

    /**
     * Sets a header with the specified {@code name} to {@code value}. This will remove all previous values
     * associated with {@code name}.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    public HttpHeadersBuilder setLong(CharSequence name, long value) {
        setters().setLong(name, value);
        return this;
    }

    /**
     * Sets a header with the specified {@code name} to {@code value}. This will remove all previous values
     * associated with {@code name}.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    public HttpHeadersBuilder setFloat(CharSequence name, float value) {
        setters().setFloat(name, value);
        return this;
    }

    /**
     * Sets a header with the specified {@code name} to {@code value}. This will remove all previous values
     * associated with {@code name}.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    public HttpHeadersBuilder setDouble(CharSequence name, double value) {
        setters().setDouble(name, value);
        return this;
    }

    /**
     * Sets a header with the specified {@code name} to {@code value}. This will remove all previous values
     * associated with {@code name}.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    public HttpHeadersBuilder setTimeMillis(CharSequence name, long value) {
        setters().setTimeMillis(name, value);
        return this;
    }

    /**
     * Removes all headers with the specified {@code name}.
     *
     * @param name the header name
     * @return {@code true} if at least one entry has been removed.
     */
    public final boolean remove(CharSequence name) {
        return contains(name) ? setters().remove(name) : false;
    }

    /**
     * Removes all headers with the specified {@code name}. Unlike {@link #remove(CharSequence)}
     * this method returns itself so that the caller can chain the invocations.
     *
     * @param name the header name
     * @return {@code this}
     */
    public HttpHeadersBuilder removeAndThen(CharSequence name) {
        if (contains(name)) {
            setters().remove(name);
        }
        return this;
    }

    /**
     * Removes all headers. After a call to this method, {@link #size()} becomes {@code 0}.
     *
     * @return {@code this}
     */
    public HttpHeadersBuilder clear() {
        if (!isEmpty()) {
            setters().clear();
        }
        return this;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + getters();
    }
}

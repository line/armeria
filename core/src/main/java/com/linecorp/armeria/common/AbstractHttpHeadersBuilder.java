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

abstract class AbstractHttpHeadersBuilder<T extends AbstractHttpHeadersBuilder<T, U>, U extends HttpHeaders>
        implements HttpHeadersBuilder {

    private int sizeHint = HttpHeadersBase.DEFAULT_SIZE_HINT;
    @Nullable
    private HttpHeadersBase delegate;
    @Nullable
    private HttpHeadersBase parent;

    AbstractHttpHeadersBuilder() {}

    AbstractHttpHeadersBuilder(HttpHeadersBase parent) {
        assert parent instanceof HttpHeaders;
        this.parent = parent;
    }

    @Override
    public abstract U build();

    @SuppressWarnings("unchecked")
    final T self() {
        return (T) this;
    }

    @Nullable
    final HttpHeadersBase delegate() {
        return delegate;
    }

    @Nullable
    final HttpHeadersBase parent() {
        return parent;
    }

    final <V extends HttpHeadersBase> V updateParent(V parent) {
        this.parent = requireNonNull(parent, "parent");
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

    @Override
    public final T sizeHint(int sizeHint) {
        checkArgument(sizeHint >= 0, "sizeHint: %s (expected: >= 0)", sizeHint);
        checkState(delegate == null, "sizeHint cannot be specified after a modification is made.");
        this.sizeHint = sizeHint;
        return self();
    }

    // Shortcuts

    @Override
    @Nullable
    public final MediaType contentType() {
        final HttpHeadersBase getters = getters();
        return getters != null ? getters.contentType() : null;
    }

    @Override
    public final T contentType(MediaType contentType) {
        setters().contentType(contentType);
        return self();
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

    @Override
    public final T endOfStream(boolean endOfStream) {
        setters().endOfStream(endOfStream);
        return self();
    }

    @Override
    @Nullable
    public final String getAndRemove(CharSequence name) {
        return contains(name) ? setters().getAndRemove(name) : null;
    }

    @Override
    public final String getAndRemove(CharSequence name, String defaultValue) {
        return contains(name) ? setters().getAndRemove(name, defaultValue)
                              : requireNonNull(defaultValue, "defaultValue");
    }

    @Override
    public final List<String> getAllAndRemove(CharSequence name) {
        return contains(name) ? setters().getAllAndRemove(name) : ImmutableList.of();
    }

    @Override
    @Nullable
    public final Integer getIntAndRemove(CharSequence name) {
        return contains(name) ? setters().getIntAndRemove(name) : null;
    }

    @Override
    public final int getIntAndRemove(CharSequence name, int defaultValue) {
        return contains(name) ? setters().getIntAndRemove(name, defaultValue) : defaultValue;
    }

    @Override
    @Nullable
    public final Long getLongAndRemove(CharSequence name) {
        return contains(name) ? setters().getLongAndRemove(name) : null;
    }

    @Override
    public final long getLongAndRemove(CharSequence name, long defaultValue) {
        return contains(name) ? setters().getLongAndRemove(name, defaultValue) : defaultValue;
    }

    @Override
    @Nullable
    public final Float getFloatAndRemove(CharSequence name) {
        return contains(name) ? setters().getFloatAndRemove(name) : null;
    }

    @Override
    public final float getFloatAndRemove(CharSequence name, float defaultValue) {
        return contains(name) ? setters().getFloatAndRemove(name, defaultValue) : defaultValue;
    }

    @Override
    @Nullable
    public final Double getDoubleAndRemove(CharSequence name) {
        return contains(name) ? setters().getDoubleAndRemove(name) : null;
    }

    @Override
    public final double getDoubleAndRemove(CharSequence name, double defaultValue) {
        return contains(name) ? setters().getDoubleAndRemove(name, defaultValue) : defaultValue;
    }

    @Override
    @Nullable
    public final Long getTimeMillisAndRemove(CharSequence name) {
        return contains(name) ? setters().getTimeMillisAndRemove(name) : null;
    }

    @Override
    public final long getTimeMillisAndRemove(CharSequence name, long defaultValue) {
        return contains(name) ? setters().getTimeMillisAndRemove(name, defaultValue) : defaultValue;
    }

    @Override
    public final T add(CharSequence name, String value) {
        setters().add(name, value);
        return self();
    }

    @Override
    public final T add(CharSequence name, Iterable<String> values) {
        setters().add(name, values);
        return self();
    }

    @Override
    public final T add(CharSequence name, String... values) {
        setters().add(name, values);
        return self();
    }

    @Override
    public final T add(
            Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        setters().add(headers);
        return self();
    }

    @Override
    public final T addObject(CharSequence name, Object value) {
        setters().addObject(name, value);
        return self();
    }

    @Override
    public final T addObject(CharSequence name, Iterable<?> values) {
        setters().addObject(name, values);
        return self();
    }

    @Override
    public final T addObject(CharSequence name, Object... values) {
        setters().addObject(name, values);
        return self();
    }

    @Override
    public final T addObject(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        setters().addObject(headers);
        return self();
    }

    @Override
    public final T addInt(CharSequence name, int value) {
        setters().addInt(name, value);
        return self();
    }

    @Override
    public final T addLong(CharSequence name, long value) {
        setters().addLong(name, value);
        return self();
    }

    @Override
    public final T addFloat(CharSequence name, float value) {
        setters().addFloat(name, value);
        return self();
    }

    @Override
    public final T addDouble(CharSequence name, double value) {
        setters().addDouble(name, value);
        return self();
    }

    @Override
    public final T addTimeMillis(CharSequence name, long value) {
        setters().addTimeMillis(name, value);
        return self();
    }

    @Override
    public final T set(CharSequence name, String value) {
        setters().set(name, value);
        return self();
    }

    @Override
    public final T set(CharSequence name, Iterable<String> values) {
        setters().set(name, values);
        return self();
    }

    @Override
    public final T set(CharSequence name, String... values) {
        setters().set(name, values);
        return self();
    }

    @Override
    public final T set(Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        setters().set(headers);
        return self();
    }

    @Override
    public final T setIfAbsent(Iterable<? extends Entry<? extends CharSequence, String>> headers) {
        setters().setIfAbsent(headers);
        return self();
    }

    @Override
    public final T setObject(CharSequence name, Object value) {
        setters().setObject(name, value);
        return self();
    }

    @Override
    public final T setObject(CharSequence name, Iterable<?> values) {
        setters().setObject(name, values);
        return self();
    }

    @Override
    public final T setObject(CharSequence name, Object... values) {
        setters().setObject(name, values);
        return self();
    }

    @Override
    public final T setObject(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        setters().setObject(headers);
        return self();
    }

    @Override
    public final T setInt(CharSequence name, int value) {
        setters().setInt(name, value);
        return self();
    }

    @Override
    public final T setLong(CharSequence name, long value) {
        setters().setLong(name, value);
        return self();
    }

    @Override
    public final T setFloat(CharSequence name, float value) {
        setters().setFloat(name, value);
        return self();
    }

    @Override
    public final T setDouble(CharSequence name, double value) {
        setters().setDouble(name, value);
        return self();
    }

    @Override
    public final T setTimeMillis(CharSequence name, long value) {
        setters().setTimeMillis(name, value);
        return self();
    }

    @Override
    public final boolean remove(CharSequence name) {
        return contains(name) ? setters().remove(name) : false;
    }

    @Override
    public final T removeAndThen(CharSequence name) {
        if (contains(name)) {
            setters().remove(name);
        }
        return self();
    }

    @Override
    public final T clear() {
        if (!isEmpty()) {
            setters().clear();
        }
        return self();
    }

    @Override
    public final String toString() {
        return getClass().getSimpleName() + getters();
    }
}

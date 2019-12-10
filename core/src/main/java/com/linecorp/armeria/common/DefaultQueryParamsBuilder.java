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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

final class DefaultQueryParamsBuilder implements QueryParamsBuilder {

    private int sizeHint = QueryParamsBase.DEFAULT_SIZE_HINT;
    @Nullable
    private QueryParamsBase delegate;
    @Nullable
    private QueryParamsBase parent;


    DefaultQueryParamsBuilder() {}

    DefaultQueryParamsBuilder(QueryParamsBase parent) {
        assert parent instanceof QueryParams;
        this.parent = parent;
    }

    @Nullable
    @VisibleForTesting
    QueryParamsBase delegate() {
        return delegate;
    }

    @Nullable
    @VisibleForTesting
    QueryParamsBase parent() {
        return parent;
    }

    private <V extends QueryParamsBase> V updateParent(V parent) {
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
    private QueryParamsBase promoteDelegate() {
        final QueryParamsBase delegate = this.delegate;
        assert delegate != null;
        parent = delegate;
        this.delegate = null;
        return delegate;
    }

    @Nullable
    private QueryParamsBase getters() {
        if (delegate != null) {
            return delegate;
        }

        if (parent != null) {
            return parent;
        }

        return null;
    }

    private QueryParamsBase setters() {
        if (delegate != null) {
            return delegate;
        }

        if (parent != null) {
            // Make a deep copy of the parent.
            return delegate = new QueryParamsBase(parent, false);
        }

        return delegate = new QueryParamsBase(sizeHint);
    }

    @Override
    public QueryParamsBuilder sizeHint(int sizeHint) {
        checkArgument(sizeHint >= 0, "sizeHint: %s (expected: >= 0)", sizeHint);
        checkState(delegate == null, "sizeHint cannot be specified after a modification is made.");
        this.sizeHint = sizeHint;
        return this;
    }

    // Getters

    @Override
    @Nullable
    public String get(String name) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.get(name) : null;
    }

    @Override
    public String get(String name, String defaultValue) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.get(name, defaultValue)
                               : requireNonNull(defaultValue, "defaultValue");
    }

    @Override
    public List<String> getAll(String name) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.getAll(name) : ImmutableList.of();
    }

    @Override
    @Nullable
    public Integer getInt(String name) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.getInt(name) : null;
    }

    @Override
    public int getInt(String name, int defaultValue) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.getInt(name, defaultValue) : defaultValue;
    }

    @Override
    @Nullable
    public Long getLong(String name) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.getLong(name) : null;
    }

    @Override
    public long getLong(String name, long defaultValue) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.getLong(name, defaultValue) : defaultValue;
    }

    @Override
    @Nullable
    public Float getFloat(String name) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.getFloat(name) : null;
    }

    @Override
    public float getFloat(String name, float defaultValue) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.getFloat(name, defaultValue) : defaultValue;
    }

    @Override
    @Nullable
    public Double getDouble(String name) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.getDouble(name) : null;
    }

    @Override
    public double getDouble(String name, double defaultValue) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.getDouble(name, defaultValue) : defaultValue;
    }

    @Override
    @Nullable
    public Long getTimeMillis(String name) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.getTimeMillis(name) : null;
    }

    @Override
    public long getTimeMillis(String name, long defaultValue) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.getTimeMillis(name, defaultValue) : defaultValue;
    }

    @Override
    public boolean contains(String name) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.contains(name) : false;
    }

    @Override
    public boolean contains(String name, String value) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.contains(name, value) : false;
    }

    @Override
    public boolean containsObject(String name, Object value) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.containsObject(name, value) : false;
    }

    @Override
    public boolean containsInt(String name, int value) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.containsInt(name, value) : false;
    }

    @Override
    public boolean containsLong(String name, long value) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.containsLong(name, value) : false;
    }

    @Override
    public boolean containsFloat(String name, float value) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.containsFloat(name, value) : false;
    }

    @Override
    public boolean containsDouble(String name, double value) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.containsDouble(name, value) : false;
    }

    @Override
    public boolean containsTimeMillis(String name, long value) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.containsTimeMillis(name, value) : false;
    }

    @Override
    public int size() {
        if (delegate != null) {
            return delegate.size();
        }
        if (parent != null) {
            return parent.size();
        }
        return 0;
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public Set<String> names() {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.names() : ImmutableSet.of();
    }

    @Override
    public Iterator<Entry<String, String>> iterator() {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.iterator() : Collections.emptyIterator();
    }

    @Override
    public Iterator<String> valueIterator(String name) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.valueIterator(name) : Collections.emptyIterator();
    }

    @Override
    public void forEach(BiConsumer<String, String> action) {
        final QueryParamsBase getters = getters();
        if (getters != null) {
            getters.forEach(action);
        }
    }

    @Override
    public void forEachValue(String name, Consumer<String> action) {
        final QueryParamsBase getters = getters();
        if (getters != null) {
            getters.forEachValue(name, action);
        }
    }

    @Override
    public Stream<Entry<String, String>> stream() {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.stream() : Stream.empty();
    }

    @Override
    public Stream<String> valueStream(String name) {
        final QueryParamsBase getters = getters();
        return getters != null ? getters.valueStream(name) : Stream.empty();
    }

    // Setters

    @Override
    @Nullable
    public String getAndRemove(String name) {
        return contains(name) ? setters().getAndRemove(name) : null;
    }

    @Override
    public String getAndRemove(String name, String defaultValue) {
        return contains(name) ? setters().getAndRemove(name, defaultValue)
                              : requireNonNull(defaultValue, "defaultValue");
    }

    @Override
    public List<String> getAllAndRemove(String name) {
        return contains(name) ? setters().getAllAndRemove(name) : ImmutableList.of();
    }

    @Override
    @Nullable
    public Integer getIntAndRemove(String name) {
        return contains(name) ? setters().getIntAndRemove(name) : null;
    }

    @Override
    public int getIntAndRemove(String name, int defaultValue) {
        return contains(name) ? setters().getIntAndRemove(name, defaultValue) : defaultValue;
    }

    @Override
    @Nullable
    public Long getLongAndRemove(String name) {
        return contains(name) ? setters().getLongAndRemove(name) : null;
    }

    @Override
    public long getLongAndRemove(String name, long defaultValue) {
        return contains(name) ? setters().getLongAndRemove(name, defaultValue) : defaultValue;
    }

    @Override
    @Nullable
    public Float getFloatAndRemove(String name) {
        return contains(name) ? setters().getFloatAndRemove(name) : null;
    }

    @Override
    public float getFloatAndRemove(String name, float defaultValue) {
        return contains(name) ? setters().getFloatAndRemove(name, defaultValue) : defaultValue;
    }

    @Override
    @Nullable
    public Double getDoubleAndRemove(String name) {
        return contains(name) ? setters().getDoubleAndRemove(name) : null;
    }

    @Override
    public double getDoubleAndRemove(String name, double defaultValue) {
        return contains(name) ? setters().getDoubleAndRemove(name, defaultValue) : defaultValue;
    }

    @Override
    @Nullable
    public Long getTimeMillisAndRemove(String name) {
        return contains(name) ? setters().getTimeMillisAndRemove(name) : null;
    }

    @Override
    public long getTimeMillisAndRemove(String name, long defaultValue) {
        return contains(name) ? setters().getTimeMillisAndRemove(name, defaultValue) : defaultValue;
    }

    @Override
    public QueryParamsBuilder add(String name, String value) {
        setters().add(name, value);
        return this;
    }

    @Override
    public QueryParamsBuilder add(String name, Iterable<String> values) {
        setters().add(name, values);
        return this;
    }

    @Override
    public QueryParamsBuilder add(String name, String... values) {
        setters().add(name, values);
        return this;
    }

    @Override
    public QueryParamsBuilder add(Iterable<? extends Entry<String, String>> headers) {
        setters().add(headers);
        return this;
    }

    @Override
    public QueryParamsBuilder addObject(String name, Object value) {
        setters().addObject(name, value);
        return this;
    }

    @Override
    public QueryParamsBuilder addObject(String name, Iterable<?> values) {
        setters().addObject(name, values);
        return this;
    }

    @Override
    public QueryParamsBuilder addObject(String name, Object... values) {
        setters().addObject(name, values);
        return this;
    }

    @Override
    public QueryParamsBuilder addObject(Iterable<? extends Entry<String, ?>> headers) {
        setters().addObject(headers);
        return this;
    }

    @Override
    public QueryParamsBuilder addInt(String name, int value) {
        setters().addInt(name, value);
        return this;
    }

    @Override
    public QueryParamsBuilder addLong(String name, long value) {
        setters().addLong(name, value);
        return this;
    }

    @Override
    public QueryParamsBuilder addFloat(String name, float value) {
        setters().addFloat(name, value);
        return this;
    }

    @Override
    public QueryParamsBuilder addDouble(String name, double value) {
        setters().addDouble(name, value);
        return this;
    }

    @Override
    public QueryParamsBuilder addTimeMillis(String name, long value) {
        setters().addTimeMillis(name, value);
        return this;
    }

    @Override
    public QueryParamsBuilder set(String name, String value) {
        setters().set(name, value);
        return this;
    }

    @Override
    public QueryParamsBuilder set(String name, Iterable<String> values) {
        setters().set(name, values);
        return this;
    }

    @Override
    public QueryParamsBuilder set(String name, String... values) {
        setters().set(name, values);
        return this;
    }

    @Override
    public QueryParamsBuilder set(Iterable<? extends Entry<String, String>> headers) {
        setters().set(headers);
        return this;
    }

    @Override
    public QueryParamsBuilder setIfAbsent(Iterable<? extends Entry<String, String>> headers) {
        setters().setIfAbsent(headers);
        return this;
    }

    @Override
    public QueryParamsBuilder setObject(String name, Object value) {
        setters().setObject(name, value);
        return this;
    }

    @Override
    public QueryParamsBuilder setObject(String name, Iterable<?> values) {
        setters().setObject(name, values);
        return this;
    }

    @Override
    public QueryParamsBuilder setObject(String name, Object... values) {
        setters().setObject(name, values);
        return this;
    }

    @Override
    public QueryParamsBuilder setObject(Iterable<? extends Entry<String, ?>> headers) {
        setters().setObject(headers);
        return this;
    }

    @Override
    public QueryParamsBuilder setInt(String name, int value) {
        setters().setInt(name, value);
        return this;
    }

    @Override
    public QueryParamsBuilder setLong(String name, long value) {
        setters().setLong(name, value);
        return this;
    }

    @Override
    public QueryParamsBuilder setFloat(String name, float value) {
        setters().setFloat(name, value);
        return this;
    }

    @Override
    public QueryParamsBuilder setDouble(String name, double value) {
        setters().setDouble(name, value);
        return this;
    }

    @Override
    public QueryParamsBuilder setTimeMillis(String name, long value) {
        setters().setTimeMillis(name, value);
        return this;
    }

    @Override
    public boolean remove(String name) {
        return contains(name) ? setters().remove(name) : false;
    }

    @Override
    public QueryParamsBuilder removeAndThen(String name) {
        if (contains(name)) {
            setters().remove(name);
        }
        return this;
    }

    @Override
    public QueryParamsBuilder clear() {
        if (!isEmpty()) {
            setters().clear();
        }
        return this;
    }

    @Override
    public QueryParams build() {
        final QueryParamsBase delegate = delegate();
        if (delegate != null) {
            if (delegate.isEmpty()) {
                return DefaultQueryParams.EMPTY;
            } else {
                return new DefaultQueryParams(promoteDelegate());
            }
        }

        final QueryParamsBase parent = parent();
        if (parent != null) {
            if (parent instanceof QueryParams) {
                return (QueryParams) parent;
            }
            return updateParent(new DefaultQueryParams(parent));
        }

        return DefaultQueryParams.EMPTY;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + getters();
    }
}

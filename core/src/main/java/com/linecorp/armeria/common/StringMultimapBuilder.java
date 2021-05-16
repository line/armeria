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

import static com.google.common.base.MoreObjects.firstNonNull;
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

/**
 * Skeletal builder implementation of {@link StringMultimap} and/or its subtypes.
 *
 * @param <IN_NAME> the type of the user-specified names, which may be more permissive than {@link NAME}
 * @param <NAME> the actual type of the names
 * @param <CONTAINER> the type of the container object
 * @param <SELF> the type of {@code this}
 */
abstract class StringMultimapBuilder<
        IN_NAME extends CharSequence,
        NAME extends IN_NAME,
        CONTAINER extends StringMultimap<IN_NAME, NAME>,
        SELF> {

    private int sizeHint = StringMultimap.DEFAULT_SIZE_HINT;
    @Nullable
    private CONTAINER delegate;
    @Nullable
    private CONTAINER parent;

    StringMultimapBuilder() {}

    StringMultimapBuilder(CONTAINER parent) {
        this.parent = parent;
    }

    @SuppressWarnings("unchecked")
    final SELF self() {
        return (SELF) this;
    }

    @Nullable
    final CONTAINER delegate() {
        return delegate;
    }

    @Nullable
    final CONTAINER parent() {
        return parent;
    }

    final <V extends CONTAINER> V updateParent(V parent) {
        this.parent = requireNonNull(parent, "parent");
        return parent;
    }

    /**
     * Makes the current {@link #delegate()} a new {@link #parent()} and clears the current {@link #delegate()}.
     * Call this method when you create a new object derived from the {@link #delegate()},
     * so that any further modifications to this builder do not break the immutability of the created object.
     *
     * @return the {@link #delegate()}
     */
    final CONTAINER promoteDelegate() {
        final CONTAINER delegate = this.delegate;
        assert delegate != null;
        parent = delegate;
        this.delegate = null;
        return delegate;
    }

    @Nullable
    final CONTAINER getters() {
        if (delegate != null) {
            return delegate;
        }

        if (parent != null) {
            return parent;
        }

        return null;
    }

    final CONTAINER setters() {
        if (delegate != null) {
            return delegate;
        }

        if (parent != null) {
            // Make a deep copy of the parent.
            return delegate = newSetters(parent, false);
        }

        return delegate = newSetters(sizeHint);
    }

    abstract CONTAINER newSetters(int sizeHint);

    abstract CONTAINER newSetters(CONTAINER parent, boolean shallowCopy);

    public final SELF sizeHint(int sizeHint) {
        checkArgument(sizeHint >= 0, "sizeHint: %s (expected: >= 0)", sizeHint);
        checkState(delegate == null, "sizeHint cannot be specified after a modification is made.");
        this.sizeHint = sizeHint;
        return self();
    }

    // Getters

    @Nullable
    public final String get(IN_NAME name) {
        final CONTAINER getters = getters();
        return getters != null ? getters.get(name) : null;
    }

    public final String get(IN_NAME name, String defaultValue) {
        final CONTAINER getters = getters();
        return getters != null ? getters.get(name, defaultValue)
                               : requireNonNull(defaultValue, "defaultValue");
    }

    @Nullable
    public final String getLast(IN_NAME name) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getLast(name) : null;
    }

    public final String getLast(IN_NAME name, String defaultValue) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getLast(name, defaultValue)
                               : requireNonNull(defaultValue, "defaultValue");
    }

    public final List<String> getAll(IN_NAME name) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getAll(name) : ImmutableList.of();
    }

    @Nullable
    public final Integer getInt(IN_NAME name) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getInt(name) : null;
    }

    public final int getInt(IN_NAME name, int defaultValue) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getInt(name, defaultValue) : defaultValue;
    }

    @Nullable
    public final Integer getLastInt(IN_NAME name) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getLastInt(name) : null;
    }

    public final int getLastInt(IN_NAME name, int defaultValue) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getLastInt(name, defaultValue) : defaultValue;
    }

    @Nullable
    public final Long getLong(IN_NAME name) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getLong(name) : null;
    }

    public final long getLong(IN_NAME name, long defaultValue) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getLong(name, defaultValue) : defaultValue;
    }

    @Nullable
    public final Long getLastLong(IN_NAME name) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getLastLong(name) : null;
    }

    public final long getLastLong(IN_NAME name, long defaultValue) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getLastLong(name, defaultValue) : defaultValue;
    }

    @Nullable
    public final Float getFloat(IN_NAME name) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getFloat(name) : null;
    }

    public final float getFloat(IN_NAME name, float defaultValue) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getFloat(name, defaultValue) : defaultValue;
    }

    @Nullable
    public final Float getLastFloat(IN_NAME name) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getLastFloat(name) : null;
    }

    public final float getLastFloat(IN_NAME name, float defaultValue) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getLastFloat(name, defaultValue) : defaultValue;
    }

    @Nullable
    public final Double getDouble(IN_NAME name) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getDouble(name) : null;
    }

    public final double getDouble(IN_NAME name, double defaultValue) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getDouble(name, defaultValue) : defaultValue;
    }

    @Nullable
    public final Double getLastDouble(IN_NAME name) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getLastDouble(name) : null;
    }

    public final double getLastDouble(IN_NAME name, double defaultValue) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getLastDouble(name, defaultValue) : defaultValue;
    }

    @Nullable
    public final Long getTimeMillis(IN_NAME name) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getTimeMillis(name) : null;
    }

    public final long getTimeMillis(IN_NAME name, long defaultValue) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getTimeMillis(name, defaultValue) : defaultValue;
    }

    @Nullable
    public final Long getLastTimeMillis(IN_NAME name) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getLastTimeMillis(name) : null;
    }

    public final long getLastTimeMillis(IN_NAME name, long defaultValue) {
        final CONTAINER getters = getters();
        return getters != null ? getters.getLastTimeMillis(name, defaultValue) : defaultValue;
    }

    public final boolean contains(IN_NAME name) {
        final CONTAINER getters = getters();
        return getters != null ? getters.contains(name) : false;
    }

    public final boolean contains(IN_NAME name, String value) {
        final CONTAINER getters = getters();
        return getters != null ? getters.contains(name, value) : false;
    }

    public final boolean containsObject(IN_NAME name, Object value) {
        final CONTAINER getters = getters();
        return getters != null ? getters.containsObject(name, value) : false;
    }

    public final boolean containsInt(IN_NAME name, int value) {
        final CONTAINER getters = getters();
        return getters != null ? getters.containsInt(name, value) : false;
    }

    public final boolean containsLong(IN_NAME name, long value) {
        final CONTAINER getters = getters();
        return getters != null ? getters.containsLong(name, value) : false;
    }

    public final boolean containsFloat(IN_NAME name, float value) {
        final CONTAINER getters = getters();
        return getters != null ? getters.containsFloat(name, value) : false;
    }

    public final boolean containsDouble(IN_NAME name, double value) {
        final CONTAINER getters = getters();
        return getters != null ? getters.containsDouble(name, value) : false;
    }

    public final boolean containsTimeMillis(IN_NAME name, long value) {
        final CONTAINER getters = getters();
        return getters != null ? getters.containsTimeMillis(name, value) : false;
    }

    public final int size() {
        if (delegate != null) {
            return delegate.size();
        }
        if (parent != null) {
            return parent.size();
        }
        return 0;
    }

    public final boolean isEmpty() {
        return size() == 0;
    }

    public final Set<NAME> names() {
        final CONTAINER getters = getters();
        return getters != null ? getters.names() : ImmutableSet.of();
    }

    public final Iterator<Entry<NAME, String>> iterator() {
        final CONTAINER getters = getters();
        return getters != null ? getters.iterator() : Collections.emptyIterator();
    }

    public final Iterator<String> valueIterator(IN_NAME name) {
        final CONTAINER getters = getters();
        return getters != null ? getters.valueIterator(name) : Collections.emptyIterator();
    }

    public final void forEach(BiConsumer<NAME, String> action) {
        final CONTAINER getters = getters();
        if (getters != null) {
            getters.forEach(action);
        }
    }

    public final void forEachValue(IN_NAME name, Consumer<String> action) {
        final CONTAINER getters = getters();
        if (getters != null) {
            getters.forEachValue(name, action);
        }
    }

    public final Stream<Entry<NAME, String>> stream() {
        final CONTAINER getters = getters();
        return getters != null ? getters.stream() : Stream.empty();
    }

    public final Stream<String> valueStream(IN_NAME name) {
        final CONTAINER getters = getters();
        return getters != null ? getters.valueStream(name) : Stream.empty();
    }

    // Setters

    @Nullable
    public final String getAndRemove(IN_NAME name) {
        return contains(name) ? setters().getAndRemove(name) : null;
    }

    public final String getAndRemove(IN_NAME name, String defaultValue) {
        return contains(name) ? setters().getAndRemove(name, defaultValue)
                              : requireNonNull(defaultValue, "defaultValue");
    }

    public final List<String> getAllAndRemove(IN_NAME name) {
        return contains(name) ? setters().getAllAndRemove(name) : ImmutableList.of();
    }

    @Nullable
    public final Integer getIntAndRemove(IN_NAME name) {
        return contains(name) ? setters().getIntAndRemove(name) : null;
    }

    public final int getIntAndRemove(IN_NAME name, int defaultValue) {
        return contains(name) ? setters().getIntAndRemove(name, defaultValue) : defaultValue;
    }

    @Nullable
    public final Long getLongAndRemove(IN_NAME name) {
        return contains(name) ? setters().getLongAndRemove(name) : null;
    }

    public final long getLongAndRemove(IN_NAME name, long defaultValue) {
        return contains(name) ? setters().getLongAndRemove(name, defaultValue) : defaultValue;
    }

    @Nullable
    public final Float getFloatAndRemove(IN_NAME name) {
        return contains(name) ? setters().getFloatAndRemove(name) : null;
    }

    public final float getFloatAndRemove(IN_NAME name, float defaultValue) {
        return contains(name) ? setters().getFloatAndRemove(name, defaultValue) : defaultValue;
    }

    @Nullable
    public final Double getDoubleAndRemove(IN_NAME name) {
        return contains(name) ? setters().getDoubleAndRemove(name) : null;
    }

    public final double getDoubleAndRemove(IN_NAME name, double defaultValue) {
        return contains(name) ? setters().getDoubleAndRemove(name, defaultValue) : defaultValue;
    }

    @Nullable
    public final Long getTimeMillisAndRemove(IN_NAME name) {
        return contains(name) ? setters().getTimeMillisAndRemove(name) : null;
    }

    public final long getTimeMillisAndRemove(IN_NAME name, long defaultValue) {
        return contains(name) ? setters().getTimeMillisAndRemove(name, defaultValue) : defaultValue;
    }

    public final SELF add(IN_NAME name, String value) {
        setters().add(name, value);
        return self();
    }

    public final SELF add(IN_NAME name, Iterable<String> values) {
        setters().add(name, values);
        return self();
    }

    public final SELF add(IN_NAME name, String... values) {
        setters().add(name, values);
        return self();
    }

    public final SELF add(
            Iterable<? extends Entry<? extends IN_NAME, String>> entries) {
        setters().add(entries);
        return self();
    }

    public final SELF addObject(IN_NAME name, Object value) {
        setters().addObject(name, value);
        return self();
    }

    public final SELF addObject(IN_NAME name, Iterable<?> values) {
        setters().addObject(name, values);
        return self();
    }

    public final SELF addObject(IN_NAME name, Object... values) {
        setters().addObject(name, values);
        return self();
    }

    public final SELF addObject(Iterable<? extends Entry<? extends IN_NAME, ?>> entries) {
        setters().addObject(entries);
        return self();
    }

    public final SELF addInt(IN_NAME name, int value) {
        setters().addInt(name, value);
        return self();
    }

    public final SELF addLong(IN_NAME name, long value) {
        setters().addLong(name, value);
        return self();
    }

    public final SELF addFloat(IN_NAME name, float value) {
        setters().addFloat(name, value);
        return self();
    }

    public final SELF addDouble(IN_NAME name, double value) {
        setters().addDouble(name, value);
        return self();
    }

    public final SELF addTimeMillis(IN_NAME name, long value) {
        setters().addTimeMillis(name, value);
        return self();
    }

    public final SELF set(IN_NAME name, String value) {
        setters().set(name, value);
        return self();
    }

    public final SELF set(IN_NAME name, Iterable<String> values) {
        setters().set(name, values);
        return self();
    }

    public final SELF set(IN_NAME name, String... values) {
        setters().set(name, values);
        return self();
    }

    public final SELF set(Iterable<? extends Entry<? extends IN_NAME, String>> entries) {
        setters().set(entries);
        return self();
    }

    public final SELF setIfAbsent(Iterable<? extends Entry<? extends IN_NAME, String>> entries) {
        setters().setIfAbsent(entries);
        return self();
    }

    public final SELF setObject(IN_NAME name, Object value) {
        setters().setObject(name, value);
        return self();
    }

    public final SELF setObject(IN_NAME name, Iterable<?> values) {
        setters().setObject(name, values);
        return self();
    }

    public final SELF setObject(IN_NAME name, Object... values) {
        setters().setObject(name, values);
        return self();
    }

    public final SELF setObject(Iterable<? extends Entry<? extends IN_NAME, ?>> entries) {
        setters().setObject(entries);
        return self();
    }

    public final SELF setInt(IN_NAME name, int value) {
        setters().setInt(name, value);
        return self();
    }

    public final SELF setLong(IN_NAME name, long value) {
        setters().setLong(name, value);
        return self();
    }

    public final SELF setFloat(IN_NAME name, float value) {
        setters().setFloat(name, value);
        return self();
    }

    public final SELF setDouble(IN_NAME name, double value) {
        setters().setDouble(name, value);
        return self();
    }

    public final SELF setTimeMillis(IN_NAME name, long value) {
        setters().setTimeMillis(name, value);
        return self();
    }

    public final boolean remove(IN_NAME name) {
        return contains(name) ? setters().remove(name) : false;
    }

    public final SELF removeAndThen(IN_NAME name) {
        if (contains(name)) {
            setters().remove(name);
        }
        return self();
    }

    public final SELF clear() {
        if (!isEmpty()) {
            setters().clear();
        }
        return self();
    }

    @Override
    public final String toString() {
        return getClass().getSimpleName() + firstNonNull(getters(), "[]");
    }
}

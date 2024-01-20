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
/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.linecorp.armeria.common;

import static com.linecorp.armeria.internal.common.util.StringUtil.toBoolean;
import static io.netty.util.internal.MathUtil.findNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.StringUtil;

import io.netty.handler.codec.DateFormatter;
import io.netty.util.AsciiString;

/**
 * The base container implementation of {@link HttpHeaders}, {@link HttpHeadersBuilder}, {@link QueryParams}
 * and {@link QueryParamsBuilder}.
 *
 * @param <IN_NAME> the type of the user-specified names, which may be more permissive than {@link NAME}
 * @param <NAME> the actual type of the names
 *
 * @see HttpHeadersBase
 * @see QueryParamsBase
 */
abstract class StringMultimap<IN_NAME extends CharSequence, NAME extends IN_NAME>
        implements StringMultimapGetters<IN_NAME, NAME> {

    static final int DEFAULT_SIZE_HINT = 16;

    /**
     * Constant used to seed the hash code generation. Could be anything but this was borrowed from murmur3.
     */
    static final int HASH_CODE_SEED = 0xc2b2ae35;

    // XXX(anuraaga): It could be an interesting future optimization if we can use something similar
    //                to an EnumSet when it's applicable, with just one each of commonly known header names.
    //                It should be very common.
    @VisibleForTesting
    final Entry[] entries;
    private final byte hashMask;

    private final Entry firstGroupHead;
    private Entry secondGroupHead;

    int size;

    StringMultimap(int sizeHint) {
        // Enforce a bound of [2, 128] because hashMask is a byte. The max possible value of hashMask is
        // one less than the length of this array, and we want the mask to be > 0.
        @SuppressWarnings("unchecked")
        final Entry[] newEntries = (Entry[]) Array.newInstance(
                Entry.class, findNextPositivePowerOfTwo(max(2, min(sizeHint, 128))));
        entries = newEntries;
        hashMask = (byte) (entries.length - 1);
        firstGroupHead = secondGroupHead = new Entry();
    }

    /**
     * Creates a shallow or deep copy of the specified {@link StringMultimap}.
     */
    StringMultimap(StringMultimap<IN_NAME, NAME> parent, boolean shallowCopy) {
        hashMask = parent.hashMask;

        if (shallowCopy) {
            entries = parent.entries;
            firstGroupHead = parent.firstGroupHead;
            secondGroupHead = parent.secondGroupHead;
            size = parent.size;
        } else {
            @SuppressWarnings("unchecked")
            final Entry[] newEntries = (Entry[]) Array.newInstance(Entry.class, parent.entries.length);
            entries = newEntries;
            firstGroupHead = secondGroupHead = new Entry();
            final boolean succeeded = addFast(parent);
            assert succeeded;
        }
    }

    /**
     * Creates a deep copy of the specified {@link StringMultimapGetters}.
     */
    StringMultimap(StringMultimapGetters<IN_NAME, NAME> parent) {
        this(parent.size());
        assert !(parent instanceof StringMultimap);
        addSlow(parent);
    }

    // Extension points

    /**
     * Returns the hash code of the specified {@code name}. Note that the following must be invariant
     * for this class to function properly:
     *
     * <pre>{@code
     * hashName(name) == hashName(normalizeName(name))
     * }</pre>
     */
    abstract int hashName(IN_NAME name);

    /**
     * Tells whether the two specified names are equal to each other. Note that the following must be invariant
     * for this class to function properly:
     *
     * <pre>{@code
     * nameEquals(a, b) == nameEquals(a, normalizeName(b))
     * }</pre>
     */
    abstract boolean nameEquals(NAME a, IN_NAME b);

    /**
     * Tells whether the specified {@code name} belongs to the first group in iteration order.
     * The second-group entries will appear after the first-group entries in {@link #names()},
     * {@link #iterator()} and {@link #stream()}.
     */
    abstract boolean isFirstGroup(NAME name);

    /**
     * Normalizes the given user-specified {@code name} into {@link NAME}.
     * See {@link #hashName(CharSequence)} and {@link #nameEquals(CharSequence, CharSequence)}
     * for the invariance your implementation must meet.
     */
    abstract NAME normalizeName(IN_NAME name);

    /**
     * Validates the given user-specified {@code value}.
     */
    abstract void validateValue(String value);

    // Getters

    @Override
    @Nullable
    public final String get(IN_NAME name) {
        requireNonNull(name, "name");
        final int h = hashName(name);
        final int i = index(h);
        Entry e = entries[i];
        String value = null;
        // loop until the first entry was found
        while (e != null) {
            if (e.hash == h) {
                final NAME currentName = e.key;
                if (currentName != null && nameEquals(currentName, name)) {
                    value = e.value;
                }
            }
            e = e.next;
        }
        return value;
    }

    @Override
    public final String get(IN_NAME name, String defaultValue) {
        requireNonNull(defaultValue, "defaultValue");
        final String value = get(name);
        return value != null ? value : defaultValue;
    }

    @Nullable
    @Override
    public String getLast(IN_NAME name) {
        requireNonNull(name, "name");
        final int h = hashName(name);
        final int i = index(h);
        Entry e = entries[i];
        while (e != null) {
            if (e.hash == h) {
                final NAME currentName = e.key;
                if (currentName != null && nameEquals(currentName, name)) {
                    return e.value;
                }
            }
            e = e.next;
        }
        return null;
    }

    @Override
    public final String getLast(IN_NAME name, String defaultValue) {
        requireNonNull(defaultValue, "defaultValue");
        final String value = getLast(name);
        return value != null ? value : defaultValue;
    }

    @Override
    public final List<String> getAll(IN_NAME name) {
        requireNonNull(name, "name");
        return getAllReversed(name).reverse();
    }

    private ImmutableList<String> getAllReversed(IN_NAME name) {
        final int h = hashName(name);
        final int i = index(h);
        Entry e = entries[i];

        if (e == null) {
            return ImmutableList.of();
        }

        final ImmutableList.Builder<String> builder = ImmutableList.builder();
        do {
            if (e.hash == h) {
                final NAME currentName = e.key;
                if (currentName != null && nameEquals(currentName, name)) {
                    builder.add(e.getValue());
                }
            }
            e = e.next;
        } while (e != null);
        return builder.build();
    }

    @Nullable
    @Override
    public Boolean getBoolean(IN_NAME name) {
        final String v = get(name);
        if (v == null) {
            return null;
        }
        return toBoolean(v, false);
    }

    @Override
    public boolean getBoolean(IN_NAME name, boolean defaultValue) {
        final Boolean v = getBoolean(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    @Override
    public Boolean getLastBoolean(IN_NAME name) {
        final String v = getLast(name);
        if (v == null) {
            return null;
        }
        return toBoolean(v, false);
    }

    @Override
    public boolean getLastBoolean(IN_NAME name, boolean defaultValue) {
        final Boolean v = getLastBoolean(name);
        return v != null ? v : defaultValue;
    }

    @Override
    @Nullable
    public final Integer getInt(IN_NAME name) {
        final String v = get(name);
        return toInteger(v);
    }

    @Override
    public final int getInt(IN_NAME name, int defaultValue) {
        final Integer v = getInt(name);
        return v != null ? v : defaultValue;
    }

    @Override
    @Nullable
    public final Integer getLastInt(IN_NAME name) {
        final String v = getLast(name);
        return toInteger(v);
    }

    @Override
    public final int getLastInt(IN_NAME name, int defaultValue) {
        final Integer v = getLastInt(name);
        return v != null ? v : defaultValue;
    }

    @Override
    @Nullable
    public final Long getLong(IN_NAME name) {
        final String v = get(name);
        return toLong(v);
    }

    @Override
    public final long getLong(IN_NAME name, long defaultValue) {
        final Long v = getLong(name);
        return v != null ? v : defaultValue;
    }

    @Override
    @Nullable
    public final Long getLastLong(IN_NAME name) {
        final String v = getLast(name);
        return toLong(v);
    }

    @Override
    public final long getLastLong(IN_NAME name, long defaultValue) {
        final Long v = getLastLong(name);
        return v != null ? v : defaultValue;
    }

    @Override
    @Nullable
    public final Float getFloat(IN_NAME name) {
        final String v = get(name);
        return toFloat(v);
    }

    @Override
    public final float getFloat(IN_NAME name, float defaultValue) {
        final Float v = getFloat(name);
        return v != null ? v : defaultValue;
    }

    @Override
    @Nullable
    public final Float getLastFloat(IN_NAME name) {
        final String v = getLast(name);
        return toFloat(v);
    }

    @Override
    public final float getLastFloat(IN_NAME name, float defaultValue) {
        final Float v = getLastFloat(name);
        return v != null ? v : defaultValue;
    }

    @Override
    @Nullable
    public final Double getDouble(IN_NAME name) {
        final String v = get(name);
        return toDouble(v);
    }

    @Override
    public final double getDouble(IN_NAME name, double defaultValue) {
        final Double v = getDouble(name);
        return v != null ? v : defaultValue;
    }

    @Override
    @Nullable
    public final Double getLastDouble(IN_NAME name) {
        final String v = getLast(name);
        return toDouble(v);
    }

    @Override
    public final double getLastDouble(IN_NAME name, double defaultValue) {
        final Double v = getLastDouble(name);
        return v != null ? v : defaultValue;
    }

    @Override
    @Nullable
    public final Long getTimeMillis(IN_NAME name) {
        final String v = get(name);
        return toTimeMillis(v);
    }

    @Override
    public final long getTimeMillis(IN_NAME name, long defaultValue) {
        final Long v = getTimeMillis(name);
        return v != null ? v : defaultValue;
    }

    @Override
    @Nullable
    public final Long getLastTimeMillis(IN_NAME name) {
        final String v = getLast(name);
        return toTimeMillis(v);
    }

    @Override
    public final long getLastTimeMillis(IN_NAME name, long defaultValue) {
        final Long v = getLastTimeMillis(name);
        return v != null ? v : defaultValue;
    }

    @Override
    public final boolean contains(IN_NAME name) {
        requireNonNull(name, "name");
        final int h = hashName(name);
        final int i = index(h);
        Entry e = entries[i];
        // Return true as soon as an entry with a matching name is found.
        while (e != null) {
            if (e.hash == h) {
                final NAME currentName = e.key;
                if (currentName != null && nameEquals(currentName, name)) {
                    return true;
                }
            }
            e = e.next;
        }
        return false;
    }

    @Override
    public final boolean contains(IN_NAME name, String value) {
        return contains(name, actual -> AsciiString.contentEquals(actual, value));
    }

    private boolean contains(IN_NAME name, Predicate<String> containsValuePredicate) {
        requireNonNull(name, "name");
        requireNonNull(containsValuePredicate, "containsValuePredicate");
        final int h = hashName(name);
        final int i = index(h);
        Entry e = entries[i];
        while (e != null) {
            if (e.hash == h) {
                final NAME currentName = e.key;
                if (currentName != null && nameEquals(currentName, name) &&
                    containsValuePredicate.test(e.value)) {
                    return true;
                }
            }
            e = e.next;
        }
        return false;
    }

    @Override
    public final boolean containsObject(IN_NAME name, Object value) {
        requireNonNull(value, "value");
        return contains(name, fromObject(value));
    }

    @Override
    public final boolean containsBoolean(IN_NAME name, boolean value) {
        return contains(name, actual -> {
            final Boolean maybeBoolean = toBoolean(actual, false);
            return maybeBoolean != null && maybeBoolean == value;
        });
    }

    @Override
    public final boolean containsInt(IN_NAME name, int value) {
        return contains(name, StringUtil.toString(value));
    }

    @Override
    public final boolean containsLong(IN_NAME name, long value) {
        return contains(name, StringUtil.toString(value));
    }

    @Override
    public final boolean containsFloat(IN_NAME name, float value) {
        return contains(name, String.valueOf(value));
    }

    @Override
    public final boolean containsDouble(IN_NAME name, double value) {
        return contains(name, String.valueOf(value));
    }

    @Override
    public final boolean containsTimeMillis(IN_NAME name, long value) {
        return contains(name, fromTimeMillis(value));
    }

    @Override
    public final int size() {
        return size;
    }

    @Override
    public final boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public final Set<NAME> names() {
        if (isEmpty()) {
            return ImmutableSet.of();
        }
        final ImmutableSet.Builder<NAME> builder = ImmutableSet.builder();
        Entry e = firstGroupHead.after;
        while (e != firstGroupHead) {
            builder.add(e.getKey());
            e = e.after;
        }
        return builder.build();
    }

    @Override
    public final Iterator<Map.Entry<NAME, String>> iterator() {
        return new EntryIterator();
    }

    @Override
    public final Iterator<String> valueIterator(IN_NAME name) {
        return getAll(name).iterator();
    }

    @Override
    public final void forEach(BiConsumer<NAME, String> action) {
        requireNonNull(action, "action");
        for (Map.Entry<NAME, String> e : this) {
            action.accept(e.getKey(), e.getValue());
        }
    }

    @Override
    public final void forEachValue(IN_NAME name, Consumer<String> action) {
        requireNonNull(name, "name");
        requireNonNull(action, "action");
        for (final Iterator<String> i = valueIterator(name); i.hasNext();) {
            action.accept(i.next());
        }
    }

    // Mutators

    @Nullable
    final String getAndRemove(IN_NAME name) {
        requireNonNull(name, "name");
        final int h = hashName(name);
        return removeAndNotify(h, index(h), name, true);
    }

    final String getAndRemove(IN_NAME name, String defaultValue) {
        requireNonNull(defaultValue, "defaultValue");
        final String value = getAndRemove(name);
        return value != null ? value : defaultValue;
    }

    final List<String> getAllAndRemove(IN_NAME name) {
        final List<String> all = getAll(name);
        if (!all.isEmpty()) {
            remove(name);
        }
        return all;
    }

    @Nullable
    final Integer getIntAndRemove(IN_NAME name) {
        final String v = getAndRemove(name);
        return toInteger(v);
    }

    final int getIntAndRemove(IN_NAME name, int defaultValue) {
        final Integer v = getIntAndRemove(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    final Long getLongAndRemove(IN_NAME name) {
        final String v = getAndRemove(name);
        return toLong(v);
    }

    final long getLongAndRemove(IN_NAME name, long defaultValue) {
        final Long v = getLongAndRemove(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    final Float getFloatAndRemove(IN_NAME name) {
        final String v = getAndRemove(name);
        return toFloat(v);
    }

    final float getFloatAndRemove(IN_NAME name, float defaultValue) {
        final Float v = getFloatAndRemove(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    final Double getDoubleAndRemove(IN_NAME name) {
        final String v = getAndRemove(name);
        return toDouble(v);
    }

    final double getDoubleAndRemove(IN_NAME name, double defaultValue) {
        final Double v = getDoubleAndRemove(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    final Long getTimeMillisAndRemove(IN_NAME name) {
        final String v = getAndRemove(name);
        return toTimeMillis(v);
    }

    final long getTimeMillisAndRemove(IN_NAME name, long defaultValue) {
        final Long v = getTimeMillisAndRemove(name);
        return v != null ? v : defaultValue;
    }

    final void add(IN_NAME name, String value) {
        final NAME normalizedName = normalizeName(name);
        requireNonNull(value, "value");
        final int h = hashName(normalizedName);
        final int i = index(h);
        addAndNotify(h, i, normalizedName, value, true);
    }

    final void add(IN_NAME name, Iterable<String> values) {
        addAndNotify(name, values, true);
    }

    final void add(IN_NAME name, String... values) {
        final NAME normalizedName = normalizeName(name);
        requireNonNull(values, "values");
        final int h = hashName(normalizedName);
        final int i = index(h);
        for (String v : values) {
            requireNonNullElement(values, v);
            addAndNotify(h, i, normalizedName, v, false);
        }
        onChange(normalizedName);
    }

    final void add(Iterable<? extends Map.Entry<? extends IN_NAME, String>> entries) {
        if (entries == this) {
            throw new IllegalArgumentException("can't add to itself.");
        }

        if (!addFast(entries)) {
            addSlow(entries);
        }
    }

    /**
     * Only adds the specified {@code name} and {@code values}, and do not notify the changes via
     * {@link #onChange(CharSequence)}.
     */
    final void addWithoutNotifying(IN_NAME name, Iterable<String> values) {
        addAndNotify(name, values, false);
    }

    private void addAndNotify(IN_NAME name, Iterable<String> values, boolean notifyChange) {
        final NAME normalizedName = normalizeName(name);
        requireNonNull(values, "values");
        final int h = hashName(normalizedName);
        final int i = index(h);
        for (String v : values) {
            requireNonNullElement(values, v);
            addAndNotify(h, i, normalizedName, v, false);
        }
        if (notifyChange) {
            onChange(normalizedName);
        }
    }

    private void addAndNotify(int h, int i, NAME name, String value, boolean notifyChange) {
        validateValue(value);
        // Update the hash table.
        entries[i] = new Entry(h, name, value, entries[i]);
        ++size;
        if (notifyChange) {
            onChange(name);
        }
    }

    private void addObjectAndNotify(NAME normalizedName, Object value, boolean notifyChange) {
        requireNonNull(value, "value");
        final int h = hashName(normalizedName);
        final int i = index(h);
        addAndNotify(h, i, normalizedName, fromObject(value), notifyChange);
    }

    private void addObjectAndNotify(IN_NAME name, Iterable<?> values, boolean notifyChange) {
        final NAME normalizedName = normalizeName(name);
        requireNonNull(values, "values");
        for (Object v : values) {
            requireNonNullElement(values, v);
            addObjectAndNotify(normalizedName, v, false);
        }
        if (notifyChange) {
            onChange(normalizedName);
        }
    }

    final void addObjectWithoutNotifying(IN_NAME name, Iterable<?> values) {
        addObjectAndNotify(name, values, false);
    }

    final void addObject(IN_NAME name, Object value) {
        final NAME normalizedName = normalizeName(name);
        requireNonNull(value, "value");
        addObjectAndNotify(normalizedName, fromObject(value), true);
    }

    final void addObject(IN_NAME name, Iterable<?> values) {
        addObjectAndNotify(name, values, true);
    }

    final void addObject(IN_NAME name, Object... values) {
        final NAME normalizedName = normalizeName(name);
        requireNonNull(values, "values");
        for (Object v : values) {
            requireNonNullElement(values, v);
            addObjectAndNotify(normalizedName, v, false);
        }
        onChange(normalizedName);
    }

    void addObject(Iterable<? extends Map.Entry<? extends IN_NAME, ?>> entries) {
        if (entries == this) {
            throw new IllegalArgumentException("can't add to itself.");
        }

        if (!addFast(entries)) {
            addObjectSlow(entries);
        }
    }

    final void addInt(IN_NAME name, int value) {
        add(name, StringUtil.toString(value));
    }

    final void addLong(IN_NAME name, long value) {
        add(name, StringUtil.toString(value));
    }

    final void addFloat(IN_NAME name, float value) {
        add(name, String.valueOf(value));
    }

    final void addDouble(IN_NAME name, double value) {
        add(name, String.valueOf(value));
    }

    final void addTimeMillis(IN_NAME name, long value) {
        add(name, fromTimeMillis(value));
    }

    final void set(IN_NAME name, String value) {
        setAndNotify(name, value, true);
    }

    final void set(IN_NAME name, Iterable<String> values) {
        final NAME normalizedName = normalizeName(name);
        requireNonNull(values, "values");

        final int h = hashName(normalizedName);
        final int i = index(h);

        removeAndNotify(h, i, normalizedName, true);
        for (String v : values) {
            requireNonNullElement(values, v);
            addAndNotify(h, i, normalizedName, v, false);
        }
    }

    final void set(IN_NAME name, String... values) {
        final NAME normalizedName = normalizeName(name);
        requireNonNull(values, "values");

        final int h = hashName(normalizedName);
        final int i = index(h);

        removeAndNotify(h, i, normalizedName, true);
        for (String v : values) {
            requireNonNullElement(values, v);
            addAndNotify(h, i, normalizedName, v, false);
        }
    }

    final void set(Iterable<? extends Map.Entry<? extends IN_NAME, String>> entries) {
        requireNonNull(entries, "entries");
        if (entries == this) {
            return;
        }

        for (Map.Entry<? extends IN_NAME, String> e : entries) {
            remove(e.getKey());
        }

        if (!addFast(entries)) {
            addSlow(entries);
        }
    }

    /**
     * Only sets the specified {@code name} and {@code value}, and do not notify the change via
     * {@link #onChange(CharSequence)}.
     */
    final void setWithoutNotifying(IN_NAME name, String value) {
        setAndNotify(name, value, false);
    }

    private void setAndNotify(IN_NAME name, String value, boolean notifyChange) {
        final NAME normalizedName = normalizeName(name);
        requireNonNull(value, "value");
        final int h = hashName(normalizedName);
        final int i = index(h);
        removeAndNotify(h, i, normalizedName, notifyChange);
        addAndNotify(h, i, normalizedName, value, false);
    }

    final StringMultimap<IN_NAME, NAME> setIfAbsent(
            Iterable<? extends Map.Entry<? extends IN_NAME, String>> entries) {
        requireNonNull(entries, "entries");
        final Set<NAME> existingNames = names();
        if (!setIfAbsentFast(entries, existingNames)) {
            setIfAbsentSlow(entries, existingNames);
        }
        return this;
    }

    /**
     * Invoked when a value associated with the specified {@code name} is added or removed.
     */
    void onChange(NAME name) {}

    /**
     * Invoked when all values are cleared.
     */
    void onClear() {}

    private boolean setIfAbsentFast(Iterable<? extends Map.Entry<? extends IN_NAME, String>> entries,
                                    Set<NAME> existingNames) {

        if (!(entries instanceof StringMultimap)) {
            return false;
        }

        @SuppressWarnings("unchecked")
        final StringMultimap<IN_NAME, NAME> multimap = (StringMultimap<IN_NAME, NAME>) entries;
        Entry e = multimap.firstGroupHead.after;

        while (e != multimap.firstGroupHead) {
            final NAME key = e.key;
            final String value = e.value;
            assert key != null;
            assert value != null;
            if (!existingNames.contains(key)) {
                addAndNotify(e.hash, index(e.hash), key, value, true);
            }
            e = e.after;
        }

        return true;
    }

    private void setIfAbsentSlow(Iterable<? extends Map.Entry<? extends IN_NAME, String>> entries,
                                 Set<NAME> existingNames) {

        for (Map.Entry<? extends IN_NAME, String> e : entries) {
            final NAME key = normalizeName(e.getKey());
            if (!existingNames.contains(key)) {
                add(key, e.getValue());
            }
        }
    }

    final void setObject(IN_NAME name, Object value) {
        requireNonNull(value, "value");
        set(name, fromObject(value));
    }

    final void setObject(IN_NAME name, Iterable<?> values) {
        final NAME normalizedName = normalizeName(name);
        requireNonNull(values, "values");

        final int h = hashName(normalizedName);
        final int i = index(h);

        removeAndNotify(h, i, normalizedName, true);
        for (Object v : values) {
            requireNonNullElement(values, v);
            addAndNotify(h, i, normalizedName, fromObject(v), false);
        }
    }

    final void setObject(IN_NAME name, Object... values) {
        final NAME normalizedName = normalizeName(name);
        requireNonNull(values, "values");

        final int h = hashName(normalizedName);
        final int i = index(h);

        removeAndNotify(h, i, normalizedName, true);
        for (Object v : values) {
            requireNonNullElement(values, v);
            addAndNotify(h, i, normalizedName, fromObject(v), false);
        }
    }

    final void setObject(Iterable<? extends Map.Entry<? extends IN_NAME, ?>> entries) {
        requireNonNull(entries, "entries");
        if (entries == this) {
            return;
        }

        for (Map.Entry<? extends IN_NAME, ?> e : entries) {
            remove(e.getKey());
        }

        if (!addFast(entries)) {
            addObjectSlow(entries);
        }
    }

    final void setInt(IN_NAME name, int value) {
        set(name, StringUtil.toString(value));
    }

    final void setLong(IN_NAME name, long value) {
        set(name, StringUtil.toString(value));
    }

    final void setFloat(IN_NAME name, float value) {
        set(name, String.valueOf(value));
    }

    final void setDouble(IN_NAME name, double value) {
        set(name, String.valueOf(value));
    }

    final void setTimeMillis(IN_NAME name, long value) {
        set(name, fromTimeMillis(value));
    }

    final boolean remove(IN_NAME name) {
        requireNonNull(name, "name");
        final int h = hashName(name);
        return removeAndNotify(h, index(h), name, true) != null;
    }

    final void clear() {
        Arrays.fill(entries, null);
        secondGroupHead = firstGroupHead.before = firstGroupHead.after = firstGroupHead;
        size = 0;
        onClear();
    }

    private static void requireNonNullElement(Object values, @Nullable Object e) {
        if (e == null) {
            throw new NullPointerException("values contains null: " + values);
        }
    }

    private int index(int hash) {
        return hash & hashMask;
    }

    private boolean addFast(Iterable<? extends Map.Entry<? extends IN_NAME, ?>> entries) {
        if (!(entries instanceof StringMultimap)) {
            return false;
        }

        @SuppressWarnings("unchecked")
        final StringMultimap<IN_NAME, NAME> multimap = (StringMultimap<IN_NAME, NAME>) entries;
        Entry e = multimap.firstGroupHead.after;
        while (e != multimap.firstGroupHead) {
            final NAME key = e.key;
            final String value = e.value;
            assert key != null;
            assert value != null;
            addAndNotify(e.hash, index(e.hash), key, value, true);
            e = e.after;
        }

        return true;
    }

    private void addSlow(Iterable<? extends Map.Entry<? extends IN_NAME, String>> entries) {
        // Slow copy
        for (Map.Entry<? extends IN_NAME, String> e : entries) {
            add(e.getKey(), e.getValue());
        }
    }

    private void addObjectSlow(Iterable<? extends Map.Entry<? extends IN_NAME, ?>> entries) {
        // Slow copy
        for (Map.Entry<? extends IN_NAME, ?> e : entries) {
            addObject(e.getKey(), e.getValue());
        }
    }

    /**
     * Removes all the entries whose hash code equals {@code h} and whose name is equal to {@code name}.
     *
     * @return the first value inserted, or {@code null} if there is no such entry.
     */
    @Nullable
    private String removeAndNotify(int h, int i, IN_NAME name, boolean notifyChange) {
        Entry e = entries[i];
        if (e == null) {
            return null;
        }

        String value = null;
        Entry next = e.next;
        while (next != null) {
            if (next.hash == h) {
                final NAME currentName = next.key;
                if (currentName != null && nameEquals(currentName, name)) {
                    value = next.value;
                    e.next = next.next;
                    next.remove();
                    --size;
                    if (notifyChange) {
                        onChange(currentName);
                    }
                } else {
                    e = next;
                }
            } else {
                e = next;
            }

            next = e.next;
        }

        e = entries[i];
        if (e.hash == h) {
            final NAME currentName = e.key;
            if (currentName != null && nameEquals(currentName, name)) {
                if (value == null) {
                    value = e.value;
                }
                entries[i] = e.next;
                e.remove();
                --size;
                if (notifyChange) {
                    onChange(currentName);
                }
            }
        }

        return value;
    }

    // Conversion functions

    @Nullable
    private static Integer toInteger(@Nullable String v) {
        try {
            return v != null ? Integer.parseInt(v) : null;
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    @Nullable
    private static Long toLong(@Nullable String v) {
        try {
            return v != null ? Long.parseLong(v) : null;
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    @Nullable
    private static Float toFloat(@Nullable String v) {
        try {
            return v != null ? Float.parseFloat(v) : null;
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    @Nullable
    private static Double toDouble(@Nullable String v) {
        try {
            return v != null ? Double.parseDouble(v) : null;
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    @Nullable
    private static Long toTimeMillis(@Nullable String v) {
        if (v == null) {
            return null;
        }

        try {
            @SuppressWarnings("UseOfObsoleteDateTimeApi")
            final Date date = DateFormatter.parseHttpDate(v);
            return date != null ? date.getTime() : null;
        } catch (Exception ignore) {
            // `parseHttpDate()` can raise an exception rather than returning `null`
            // when the given value has more than 64 characters.
            return null;
        }
    }

    private static String fromTimeMillis(long value) {
        return StringValueConverter.INSTANCE.convertTimeMillis(value);
    }

    private static String fromObject(Object value) {
        final String strVal = StringValueConverter.INSTANCE.convertObject(value);
        assert strVal != null : value + " converted to null.";
        return strVal;
    }

    // hashCode(), equals() and toString()

    @Override
    public int hashCode() {
        int result = HASH_CODE_SEED;
        for (NAME name : names()) {
            result = (result * 31 + hashName(name)) * 31 + getAll(name).hashCode();
        }
        return result;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof StringMultimapGetters)) {
            return false;
        }

        @SuppressWarnings("unchecked")
        final StringMultimapGetters<IN_NAME, NAME> that = (StringMultimapGetters<IN_NAME, NAME>) o;
        if (size() != that.size()) {
            return false;
        }

        if (that instanceof StringMultimap) {
            return equalsFast((StringMultimap<IN_NAME, NAME>) that);
        } else {
            return equalsSlow(that);
        }
    }

    private boolean equalsFast(StringMultimap<IN_NAME, NAME> that) {
        Entry e = firstGroupHead.after;
        while (e != firstGroupHead) {
            final NAME name = e.getKey();
            if (!getAllReversed(name).equals(that.getAllReversed(name))) {
                return false;
            }
            e = e.after;
        }
        return true;
    }

    private boolean equalsSlow(StringMultimapGetters<IN_NAME, NAME> that) {
        Entry e = firstGroupHead.after;
        while (e != firstGroupHead) {
            final NAME name = e.getKey();
            if (!Iterators.elementsEqual(valueIterator(name), that.valueIterator(name))) {
                return false;
            }
            e = e.after;
        }
        return true;
    }

    @Override
    public String toString() {
        if (size == 0) {
            return "[]";
        }

        final StringBuilder sb = new StringBuilder(7 + size * 20);
        sb.append('[');

        Entry e = firstGroupHead.after;
        while (e != firstGroupHead) {
            sb.append(e.key).append('=').append(e.value).append(", ");
            e = e.after;
        }

        final int length = sb.length();
        sb.setCharAt(length - 2, ']');
        return sb.substring(0, length - 1);
    }

    // Iterator implementations

    private final class EntryIterator implements Iterator<Map.Entry<NAME, String>> {
        private Entry current = firstGroupHead;

        @Override
        public boolean hasNext() {
            return current.after != firstGroupHead;
        }

        @Override
        public Map.Entry<NAME, String> next() {
            current = current.after;

            if (current == firstGroupHead) {
                throw new NoSuchElementException();
            }

            return current;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("read-only");
        }
    }

    @SuppressWarnings("ClassNameSameAsAncestorName")
    private final class Entry implements Map.Entry<NAME, String> {

        final int hash;
        @Nullable
        final NAME key;
        @Nullable
        final String value;
        /**
         * In bucket linked list.
         */
        @Nullable
        Entry next;
        /**
         * Overall insertion order linked list.
         */
        Entry before;
        Entry after;

        /**
         * Creates a new head node.
         */
        Entry() {
            hash = -1;
            key = null;
            value = null;
            before = after = this;
        }

        Entry(int hash, NAME key, String value, Entry next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;

            // Make sure the first-group entries appear first during iteration.
            if (isFirstGroup(key)) {
                after = secondGroupHead;
                before = secondGroupHead.before;
            } else {
                after = firstGroupHead;
                before = firstGroupHead.before;
                if (secondGroupHead == firstGroupHead) {
                    secondGroupHead = this;
                }
            }
            pointNeighborsToThis();
        }

        void pointNeighborsToThis() {
            before.after = this;
            after.before = this;
        }

        void remove() {
            if (this == secondGroupHead) {
                secondGroupHead = secondGroupHead.after;
            }

            before.after = after;
            after.before = before;
        }

        @Override
        public NAME getKey() {
            assert key != null;
            return key;
        }

        @Override
        public String getValue() {
            assert value != null;
            return value;
        }

        @Override
        public String setValue(String value) {
            throw new UnsupportedOperationException("read-only");
        }

        @Override
        public int hashCode() {
            return (key == null ? 0 : hashName(key)) ^ (value == null ? 0 : value.hashCode());
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof Map.Entry)) {
                return false;
            }

            @SuppressWarnings("unchecked")
            final Map.Entry<IN_NAME, String> that = (Map.Entry<IN_NAME, String>) o;
            final NAME thisKey = key;
            final IN_NAME thatKey = that.getKey();
            if (thisKey == null) {
                return thatKey == null &&
                       Objects.equals(value, that.getValue());
            } else {
                return thatKey != null &&
                       nameEquals(thisKey, thatKey) &&
                       Objects.equals(value, that.getValue());
            }
        }

        @Override
        public String toString() {
            if (key == null) {
                return "<HEAD>";
            }

            assert value != null;
            return new StringBuilder(key.length() + value.length() + 1)
                    .append(key)
                    .append('=')
                    .append(value)
                    .toString();
        }
    }
}

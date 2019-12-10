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

import static io.netty.util.internal.MathUtil.findNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;

import io.netty.handler.codec.DateFormatter;

/**
 * The base container implementation of query parameters.
 */
class QueryParamsBase implements QueryParamGetters {

    static final int DEFAULT_SIZE_HINT = 16;

    /**
     * Constant used to seed the hash code generation. Could be anything but this was borrowed from murmur3.
     */
    static final int HASH_CODE_SEED = 0xc2b2ae35;

    @VisibleForTesting
    final ParamEntry[] entries;
    private final byte hashMask;

    private final ParamEntry head;

    int size;

    QueryParamsBase(int sizeHint) {
        // Enforce a bound of [2, 128] because hashMask is a byte. The max possible value of hashMask is
        // one less than the length of this array, and we want the mask to be > 0.
        entries = new ParamEntry[findNextPositivePowerOfTwo(max(2, min(sizeHint, 128)))];
        hashMask = (byte) (entries.length - 1);
        head = new ParamEntry();
    }

    /**
     * Creates a shallow or deep copy of the specified {@link QueryParamsBase}.
     */
    QueryParamsBase(QueryParamsBase params, boolean shallowCopy) {
        hashMask = params.hashMask;

        if (shallowCopy) {
            entries = params.entries;
            head = params.head;
            size = params.size;
        } else {
            entries = new ParamEntry[params.entries.length];
            head = new ParamEntry();
            final boolean succeeded = addFast(params);
            assert succeeded;
        }
    }

    // Getters

    @Nullable
    @Override
    public final String get(String name) {
        requireNonNull(name, "name");
        final int h = name.hashCode();
        final int i = index(h);
        ParamEntry e = entries[i];
        String value = null;
        // loop until the first parameter was found
        while (e != null) {
            if (e.hash == h && keyEquals(e.key, name)) {
                value = e.value;
            }
            e = e.next;
        }
        return value;
    }

    @Override
    public final String get(String name, String defaultValue) {
        requireNonNull(defaultValue, "defaultValue");
        final String value = get(name);
        return value != null ? value : defaultValue;
    }

    @Override
    public final List<String> getAll(String name) {
        requireNonNull(name, "name");
        return getAllReversed(name).reverse();
    }

    private ImmutableList<String> getAllReversed(String name) {
        final int h = name.hashCode();
        final int i = index(h);
        ParamEntry e = entries[i];

        if (e == null) {
            return ImmutableList.of();
        }

        final ImmutableList.Builder<String> builder = ImmutableList.builder();
        do {
            if (e.hash == h && keyEquals(e.key, name)) {
                builder.add(e.getValue());
            }
            e = e.next;
        } while (e != null);
        return builder.build();
    }

    @Nullable
    @Override
    public final Integer getInt(String name) {
        final String v = get(name);
        return toInteger(v);
    }

    @Override
    public final int getInt(String name, int defaultValue) {
        final Integer v = getInt(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    @Override
    public final Long getLong(String name) {
        final String v = get(name);
        return toLong(v);
    }

    @Override
    public final long getLong(String name, long defaultValue) {
        final Long v = getLong(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    @Override
    public final Float getFloat(String name) {
        final String v = get(name);
        return toFloat(v);
    }

    @Override
    public final float getFloat(String name, float defaultValue) {
        final Float v = getFloat(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    @Override
    public final Double getDouble(String name) {
        final String v = get(name);
        return toDouble(v);
    }

    @Override
    public final double getDouble(String name, double defaultValue) {
        final Double v = getDouble(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    @Override
    public final Long getTimeMillis(String name) {
        final String v = get(name);
        return toTimeMillis(v);
    }

    @Override
    public final long getTimeMillis(String name, long defaultValue) {
        final Long v = getTimeMillis(name);
        return v != null ? v : defaultValue;
    }

    @Override
    public final boolean contains(String name) {
        requireNonNull(name, "name");
        final int h = name.hashCode();
        final int i = index(h);
        ParamEntry e = entries[i];
        // loop until the first parameter was found
        while (e != null) {
            if (e.hash == h && keyEquals(e.key, name)) {
                return true;
            }
            e = e.next;
        }
        return false;
    }

    @Override
    public final boolean contains(String name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        final int h = name.hashCode();
        final int i = index(h);
        ParamEntry e = entries[i];
        while (e != null) {
            if (e.hash == h && name.equals(e.key) && value.equals(e.value)) {
                return true;
            }
            e = e.next;
        }
        return false;
    }

    @Override
    public final boolean containsObject(String name, Object value) {
        requireNonNull(value, "value");
        return contains(name, fromObject(value));
    }

    @Override
    public final boolean containsInt(String name, int value) {
        return contains(name, String.valueOf(value));
    }

    @Override
    public final boolean containsLong(String name, long value) {
        return contains(name, String.valueOf(value));
    }

    @Override
    public final boolean containsFloat(String name, float value) {
        return contains(name, String.valueOf(value));
    }

    @Override
    public final boolean containsDouble(String name, double value) {
        return contains(name, String.valueOf(value));
    }

    @Override
    public final boolean containsTimeMillis(String name, long value) {
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
    public final Set<String> names() {
        if (isEmpty()) {
            return ImmutableSet.of();
        }
        final ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        ParamEntry e = head.after;
        while (e != head) {
            builder.add(e.getKey());
            e = e.after;
        }
        return builder.build();
    }

    @Override
    public final Iterator<Entry<String, String>> iterator() {
        return new ParamIterator();
    }

    @Override
    public final Iterator<String> valueIterator(String name) {
        return getAll(name).iterator();
    }

    @Override
    public final void forEach(BiConsumer<String, String> action) {
        requireNonNull(action, "action");
        for (Entry<String, String> e : this) {
            action.accept(e.getKey(), e.getValue());
        }
    }

    @Override
    public final void forEachValue(String name, Consumer<String> action) {
        requireNonNull(name, "name");
        requireNonNull(action, "action");
        for (final Iterator<String> i = valueIterator(name); i.hasNext();) {
            action.accept(i.next());
        }
    }

    // Mutators

    @Nullable
    final String getAndRemove(String name) {
        requireNonNull(name, "name");
        final int h = name.hashCode();
        return remove0(h, index(h), name);
    }

    final String getAndRemove(String name, String defaultValue) {
        requireNonNull(defaultValue, "defaultValue");
        final String value = getAndRemove(name);
        return value != null ? value : defaultValue;
    }

    final List<String> getAllAndRemove(String name) {
        final List<String> all = getAll(name);
        if (!all.isEmpty()) {
            remove(name);
        }
        return all;
    }

    @Nullable
    final Integer getIntAndRemove(String name) {
        final String v = getAndRemove(name);
        return toInteger(v);
    }

    final int getIntAndRemove(String name, int defaultValue) {
        final Integer v = getIntAndRemove(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    final Long getLongAndRemove(String name) {
        final String v = getAndRemove(name);
        return toLong(v);
    }

    final long getLongAndRemove(String name, long defaultValue) {
        final Long v = getLongAndRemove(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    final Float getFloatAndRemove(String name) {
        final String v = getAndRemove(name);
        return toFloat(v);
    }

    final float getFloatAndRemove(String name, float defaultValue) {
        final Float v = getFloatAndRemove(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    final Double getDoubleAndRemove(String name) {
        final String v = getAndRemove(name);
        return toDouble(v);
    }

    final double getDoubleAndRemove(String name, double defaultValue) {
        final Double v = getDoubleAndRemove(name);
        return v != null ? v : defaultValue;
    }

    @Nullable
    final Long getTimeMillisAndRemove(String name) {
        final String v = getAndRemove(name);
        return toTimeMillis(v);
    }

    final long getTimeMillisAndRemove(String name, long defaultValue) {
        final Long v = getTimeMillisAndRemove(name);
        return v != null ? v : defaultValue;
    }

    final void add(String name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        final int h = name.hashCode();
        final int i = index(h);
        add0(h, i, name, value);
    }

    final void add(String name, Iterable<String> values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        final int h = name.hashCode();
        final int i = index(h);
        for (String v : values) {
            requireNonNullElement(values, v);
            add0(h, i, name, v);
        }
    }

    final void add(String name, String... values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        final int h = name.hashCode();
        final int i = index(h);
        for (String v : values) {
            requireNonNullElement(values, v);
            add0(h, i, name, v);
        }
    }

    final void add(Iterable<? extends Entry<String, String>> params) {
        if (params == this) {
            throw new IllegalArgumentException("can't add to itself.");
        }

        if (!addFast(params)) {
            addSlow(params);
        }
    }

    final void addObject(String name, Object value) {
        requireNonNull(value, "value");
        add(name, fromObject(value));
    }

    final void addObject(String name, Iterable<?> values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        for (Object v : values) {
            requireNonNullElement(values, v);
            addObject(name, v);
        }
    }

    final void addObject(String name, Object... values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        for (Object v : values) {
            requireNonNullElement(values, v);
            addObject(name, v);
        }
    }

    void addObject(Iterable<? extends Entry<String, ?>> params) {
        if (params == this) {
            throw new IllegalArgumentException("can't add to itself.");
        }

        if (!addFast(params)) {
            addObjectSlow(params);
        }
    }

    final void addInt(String name, int value) {
        add(name, String.valueOf(value));
    }

    final void addLong(String name, long value) {
        add(name, String.valueOf(value));
    }

    final void addFloat(String name, float value) {
        add(name, String.valueOf(value));
    }

    final void addDouble(String name, double value) {
        add(name, String.valueOf(value));
    }

    final void addTimeMillis(String name, long value) {
        add(name, fromTimeMillis(value));
    }

    final void set(String name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        final int h = name.hashCode();
        final int i = index(h);
        remove0(h, i, name);
        add0(h, i, name, value);
    }

    final void set(String name, Iterable<String> values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");

        final int h = name.hashCode();
        final int i = index(h);

        remove0(h, i, name);
        for (String v : values) {
            requireNonNullElement(values, v);
            add0(h, i, name, v);
        }
    }

    final void set(String name, String... values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");

        final int h = name.hashCode();
        final int i = index(h);

        remove0(h, i, name);
        for (String v : values) {
            requireNonNullElement(values, v);
            add0(h, i, name, v);
        }
    }

    final void set(Iterable<? extends Entry<String, String>> params) {
        requireNonNull(params, "params");
        if (params == this) {
            return;
        }

        for (Entry<String, String> e : params) {
            remove(e.getKey());
        }

        if (!addFast(params)) {
            addSlow(params);
        }
    }

    public QueryParamsBase setIfAbsent(Iterable<? extends Entry<String, String>> params) {
        requireNonNull(params, "params");
        final Set<String> existingNames = names();
        if (!setIfAbsentFast(params, existingNames)) {
            setIfAbsentSlow(params, existingNames);
        }
        return this;
    }

    private boolean setIfAbsentFast(Iterable<? extends Entry<String, String>> params,
                                    Set<String> existingNames) {

        if (!(params instanceof QueryParamsBase)) {
            return false;
        }

        final QueryParamsBase paramsBase = (QueryParamsBase) params;
        ParamEntry e = paramsBase.head.after;

        while (e != paramsBase.head) {
            final String key = e.key;
            final String value = e.value;
            assert key != null;
            assert value != null;
            if (!existingNames.contains(key)) {
                add0(e.hash, index(e.hash), key, value);
            }
            e = e.after;
        }

        return true;
    }

    private void setIfAbsentSlow(Iterable<? extends Entry<String, String>> params,
                                 Set<String> existingNames) {

        for (Entry<String, String> param : params) {
            final String name = requireNonNull(param.getKey(), "name");
            if (!existingNames.contains(name)) {
                add(name, param.getValue());
            }
        }
    }

    final void setObject(String name, Object value) {
        requireNonNull(value, "value");
        set(name, fromObject(value));
    }

    final void setObject(String name, Iterable<?> values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");

        final int h = name.hashCode();
        final int i = index(h);

        remove0(h, i, name);
        for (Object v: values) {
            requireNonNullElement(values, v);
            add0(h, i, name, fromObject(v));
        }
    }

    final void setObject(String name, Object... values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");

        final int h = name.hashCode();
        final int i = index(h);

        remove0(h, i, name);
        for (Object v: values) {
            requireNonNullElement(values, v);
            add0(h, i, name, fromObject(v));
        }
    }

    final void setObject(Iterable<? extends Entry<String, ?>> params) {
        requireNonNull(params, "params");
        if (params == this) {
            return;
        }

        for (Entry<String, ?> e : params) {
            remove(e.getKey());
        }

        if (!addFast(params)) {
            addObjectSlow(params);
        }
    }

    final void setInt(String name, int value) {
        set(name, String.valueOf(value));
    }

    final void setLong(String name, long value) {
        set(name, String.valueOf(value));
    }

    final void setFloat(String name, float value) {
        set(name, String.valueOf(value));
    }

    final void setDouble(String name, double value) {
        set(name, String.valueOf(value));
    }

    final void setTimeMillis(String name, long value) {
        set(name, fromTimeMillis(value));
    }

    final boolean remove(String name) {
        requireNonNull(name, "name");
        final int h = name.hashCode();
        return remove0(h, index(h), name) != null;
    }

    final void clear() {
        Arrays.fill(entries, null);
        head.before = head.after = head;
        size = 0;
    }

    private static void requireNonNullElement(Object values, @Nullable Object e) {
        if (e == null) {
            throw new NullPointerException("values contains null: " + values);
        }
    }

    private int index(int hash) {
        return hash & hashMask;
    }

    private void add0(int h, int i, String name, String value) {
        // Update the hash table.
        entries[i] = new ParamEntry(h, name, value, entries[i]);
        ++size;
    }

    private boolean addFast(Iterable<? extends Entry<String, ?>> params) {
        if (!(params instanceof QueryParamsBase)) {
            return false;
        }

        final QueryParamsBase paramsBase = (QueryParamsBase) params;
        ParamEntry e = paramsBase.head.after;
        while (e != paramsBase.head) {
            final String key = e.key;
            final String value = e.value;
            assert key != null;
            assert value != null;
            add0(e.hash, index(e.hash), key, value);
            e = e.after;
        }

        return true;
    }

    private void addSlow(Iterable<? extends Entry<String, String>> params) {
        // Slow copy
        for (Entry<String, String> param : params) {
            add(param.getKey(), param.getValue());
        }
    }

    private void addObjectSlow(Iterable<? extends Entry<String, ?>> params) {
        // Slow copy
        for (Entry<String, ?> param : params) {
            addObject(param.getKey(), param.getValue());
        }
    }

    /**
     * Removes all the entries whose hash code equals {@code h} and whose name is equal to {@code name}.
     *
     * @return the first value inserted, or {@code null} if there is no such parameter.
     */
    @Nullable
    private String remove0(int h, int i, String name) {
        ParamEntry e = entries[i];
        if (e == null) {
            return null;
        }

        String value = null;
        ParamEntry next = e.next;
        while (next != null) {
            if (next.hash == h && keyEquals(next.key, name)) {
                value = next.value;
                e.next = next.next;
                next.remove();
                --size;
            } else {
                e = next;
            }

            next = e.next;
        }

        e = entries[i];
        if (e.hash == h && keyEquals(e.key, name)) {
            if (value == null) {
                value = e.value;
            }
            entries[i] = e.next;
            e.remove();
            --size;
        }

        return value;
    }

    private static boolean keyEquals(@Nullable String a, String b) {
        return b.equals(a);
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
    public final int hashCode() {
        int result = HASH_CODE_SEED;
        for (String name : names()) {
            result = (result * 31 + name.hashCode()) * 31 + getAll(name).hashCode();
        }
        return result;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof QueryParamGetters)) {
            return false;
        }

        final QueryParamGetters that = (QueryParamGetters) o;
        if (size() != that.size()) {
            return false;
        }

        if (that instanceof QueryParamsBase) {
            return equalsFast((QueryParamsBase) that);
        } else {
            return equalsSlow(that);
        }
    }

    private boolean equalsFast(QueryParamsBase that) {
        ParamEntry e = head.after;
        while (e != head) {
            final String name = e.getKey();
            if (!getAllReversed(name).equals(that.getAllReversed(name))) {
                return false;
            }
            e = e.after;
        }
        return true;
    }

    private boolean equalsSlow(QueryParamGetters that) {
        ParamEntry e = head.after;
        while (e != head) {
            final String name = e.getKey();
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

        ParamEntry e = head.after;
        while (e != head) {
            sb.append(e.key).append('=').append(e.value).append(", ");
            e = e.after;
        }

        final int length = sb.length();
        sb.setCharAt(length - 2, ']');
        return sb.substring(0, length - 1);
    }

    // Iterator implementations

    private final class ParamIterator implements Iterator<Entry<String, String>> {
        private ParamEntry current = head;

        @Override
        public boolean hasNext() {
            return current.after != head;
        }

        @Override
        public Entry<String, String> next() {
            current = current.after;

            if (current == head) {
                throw new NoSuchElementException();
            }

            return current;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("read-only");
        }
    }

    private final class ParamEntry implements Entry<String, String> {

        final int hash;
        @Nullable
        final String key;
        @Nullable
        final String value;
        /**
         * In bucket linked list.
         */
        @Nullable
        ParamEntry next;
        /**
         * Overall insertion order linked list.
         */
        ParamEntry before;
        ParamEntry after;

        /**
         * Creates a new head node.
         */
        ParamEntry() {
            hash = -1;
            key = null;
            value = null;
            before = after = this;
        }

        ParamEntry(int hash, String key, String value, ParamEntry next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;

            after = head;
            before = head.before;
            pointNeighborsToThis();
        }

        void pointNeighborsToThis() {
            before.after = this;
            after.before = this;
        }

        void remove() {
            before.after = after;
            after.before = before;
        }

        @Override
        public String getKey() {
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
            return (key == null ? 0 : key.hashCode()) ^ (value == null ? 0 : value.hashCode());
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof Map.Entry)) {
                return false;
            }

            final Entry<?, ?> that = (Entry<?, ?>) o;
            return Objects.equals(key, that.getKey()) && Objects.equals(value, that.getValue());
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

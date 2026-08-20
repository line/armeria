/*
 * Copyright 2023 LINE Corporation
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

import static com.linecorp.armeria.common.StringMultimap.HASH_CODE_SEED;
import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.handler.codec.DateFormatter;

/**
 * The composite container implementation which wraps {@link StringMultimap}s
 * to avoid expensive copy operations.
 *
 * <p>Adds entries only on {@code appender} and marks entries as removed only on {@code removed}
 * without modifying wrapped {@link StringMultimap}s to avoid expensive copy operations.
 *
 * <p>So any further modifications to this don't break the immutability of wrapped {@link StringMultimap}s.
 *
 * @param <IN_NAME> the type of the user-specified names, which may be more permissive than {@link NAME}
 * @param <NAME> the actual type of the names
 *
 * @see CompositeHttpHeadersBase
 */
abstract class CompositeStringMultimap<IN_NAME extends CharSequence, NAME extends IN_NAME>
        implements StringMultimapGetters<IN_NAME, NAME> {

    /**
     * The {@link StringMultimap}s have priority in the following order.
     * <pre>{@code
     * additionals (highest priority) > parents > defaults (lowest priority)
     * }</pre>
     */
    @Nullable
    private final List<StringMultimap<IN_NAME, NAME>> additionals;
    private final List<StringMultimap<IN_NAME, NAME>> parents;
    @Nullable
    private final List<StringMultimap<IN_NAME, NAME>> defaults;

    @Nullable
    private StringMultimap<IN_NAME, NAME> appender;
    private final Supplier<StringMultimap<IN_NAME, NAME>> appenderSupplier;

    @Nullable
    private List<StringMultimapGetters<IN_NAME, NAME>> delegates;

    private final Set<IN_NAME> removed = new HashSet<>();

    CompositeStringMultimap(List<StringMultimap<IN_NAME, NAME>> parents,
                            Supplier<StringMultimap<IN_NAME, NAME>> appenderSupplier) {
        this(null, parents, null, appenderSupplier);
    }

    CompositeStringMultimap(@Nullable List<StringMultimap<IN_NAME, NAME>> additionals,
                            List<StringMultimap<IN_NAME, NAME>> parents,
                            @Nullable List<StringMultimap<IN_NAME, NAME>> defaults,
                            Supplier<StringMultimap<IN_NAME, NAME>> appenderSupplier) {
        requireNonNull(parents, "parents");
        requireNonNull(appenderSupplier, "appenderSupplier");
        this.additionals = additionals;
        this.parents = parents;
        this.defaults = defaults;
        this.appenderSupplier = appenderSupplier;
    }

    protected final List<StringMultimapGetters<IN_NAME, NAME>> delegates() {
        if (delegates != null) {
            return delegates;
        }

        final ImmutableList.Builder<StringMultimapGetters<IN_NAME, NAME>> builder = ImmutableList.builder();
        if (additionals != null) {
            builder.addAll(additionals);
        }

        builder.addAll(parents);

        if (defaults != null) {
            builder.addAll(defaults);
        }

        return delegates = builder.build();
    }

    protected final StringMultimap<IN_NAME, NAME> appender() {
        if (appender != null) {
            return appender;
        }
        appender = requireNonNull(appenderSupplier.get(), "appender");

        final ImmutableList.Builder<StringMultimapGetters<IN_NAME, NAME>> builder = ImmutableList.builder();
        delegates = builder.addAll(delegates())
                           .add(appender)
                           .build();
        return appender;
    }

    // Getters

    @Override
    @Nullable
    public String get(IN_NAME name) {
        requireNonNull(name, "name");
        if (removed.contains(name)) {
            return appender != null ? appender.get(name) : null;
        }

        for (StringMultimapGetters<IN_NAME, NAME> delegate : delegates()) {
            final String res = delegate.get(name);
            if (res != null) {
                return res;
            }
        }
        return null;
    }

    @Override
    public String get(IN_NAME name, String defaultValue) {
        requireNonNull(name, "name");
        requireNonNull(defaultValue, "defaultValue");
        final String get = get(name);
        return get != null ? get : defaultValue;
    }

    @Override
    @Nullable
    public String getLast(IN_NAME name) {
        requireNonNull(name, "name");
        if (removed.contains(name)) {
            return appender != null ? appender.getLast(name) : null;
        }

        if (appender != null && appender.contains(name)) {
            return appender.getLast(name);
        }

        String getLast = null;
        if (additionals != null) {
            for (StringMultimapGetters<IN_NAME, NAME> getter : additionals) {
                final String res = getter.getLast(name);
                if (res != null) {
                    getLast = res;
                }
            }
        }

        if (getLast == null) {
            for (StringMultimapGetters<IN_NAME, NAME> parent : parents) {
                final String res = parent.getLast(name);
                if (res != null) {
                    getLast = res;
                }
            }
        }

        if (getLast == null && defaults != null) {
            for (StringMultimapGetters<IN_NAME, NAME> getter : defaults) {
                final String res = getter.getLast(name);
                if (res != null) {
                    getLast = res;
                }
            }
        }
        return getLast;
    }

    @Override
    public String getLast(IN_NAME name, String defaultValue) {
        requireNonNull(name, "name");
        requireNonNull(defaultValue, "defaultValue");
        final String getLast = getLast(name);
        return getLast != null ? getLast : defaultValue;
    }

    @Override
    public List<String> getAll(IN_NAME name) {
        requireNonNull(name, "name");
        if (removed.contains(name)) {
            return appender != null ? appender.getAll(name) : ImmutableList.of();
        }

        final ImmutableList.Builder<String> builder = ImmutableList.builder();
        boolean additionalsContain = false;
        if (additionals != null) {
            for (StringMultimapGetters<IN_NAME, NAME> getter : additionals) {
                final List<String> res = getter.getAll(name);
                if (!res.isEmpty()) {
                    builder.addAll(res);
                    additionalsContain = true;
                }
            }
        }

        boolean parentsContain = false;
        if (!additionalsContain) {
            for (StringMultimapGetters<IN_NAME, NAME> parent : parents) {
                final List<String> res = parent.getAll(name);
                if (!res.isEmpty()) {
                    builder.addAll(res);
                    parentsContain = true;
                }
            }
        }

        if (!additionalsContain && !parentsContain && defaults != null) {
            for (StringMultimapGetters<IN_NAME, NAME> getter : defaults) {
                final List<String> res = getter.getAll(name);
                if (!res.isEmpty()) {
                    builder.addAll(res);
                }
            }
        }

        if (appender != null) {
            builder.addAll(appender.getAll(name));
        }
        return builder.build();
    }

    @Override
    @Nullable
    public Boolean getBoolean(IN_NAME name) {
        requireNonNull(name, "name");
        if (removed.contains(name)) {
            return appender != null ? appender.getBoolean(name) : null;
        }

        for (StringMultimapGetters<IN_NAME, NAME> delegate : delegates()) {
            final Boolean res = delegate.getBoolean(name);
            if (res != null) {
                return res;
            }
        }
        return null;
    }

    @Override
    public boolean getBoolean(IN_NAME name, boolean defaultValue) {
        requireNonNull(name, "name");
        final Boolean getBoolean = getBoolean(name);
        return getBoolean != null ? getBoolean : defaultValue;
    }

    @Override
    @Nullable
    public Boolean getLastBoolean(IN_NAME name) {
        requireNonNull(name, "name");
        if (removed.contains(name)) {
            return appender != null ? appender.getLastBoolean(name) : null;
        }

        Boolean getLastBoolean = null;
        for (StringMultimapGetters<IN_NAME, NAME> delegate : delegates()) {
            final Boolean res = delegate.getLastBoolean(name);
            if (res != null) {
                getLastBoolean = res;
            }
        }
        return getLastBoolean;
    }

    @Override
    public boolean getLastBoolean(IN_NAME name, boolean defaultValue) {
        requireNonNull(name, "name");
        final Boolean getLastBoolean = getLastBoolean(name);
        return getLastBoolean != null ? getLastBoolean : defaultValue;
    }

    @Override
    @Nullable
    public Integer getInt(IN_NAME name) {
        requireNonNull(name, "name");
        if (removed.contains(name)) {
            return appender != null ? appender.getInt(name) : null;
        }

        for (StringMultimapGetters<IN_NAME, NAME> delegate : delegates()) {
            final Integer res = delegate.getInt(name);
            if (res != null) {
                return res;
            }
        }
        return null;
    }

    @Override
    public int getInt(IN_NAME name, int defaultValue) {
        requireNonNull(name, "name");
        final Integer getInt = getInt(name);
        return getInt != null ? getInt : defaultValue;
    }

    @Override
    @Nullable
    public Integer getLastInt(IN_NAME name) {
        requireNonNull(name, "name");
        if (removed.contains(name)) {
            return appender != null ? appender.getLastInt(name) : null;
        }

        Integer getLastInt = null;
        for (StringMultimapGetters<IN_NAME, NAME> delegate : delegates()) {
            final Integer res = delegate.getLastInt(name);
            if (res != null) {
                getLastInt = res;
            }
        }
        return getLastInt;
    }

    @Override
    public int getLastInt(IN_NAME name, int defaultValue) {
        requireNonNull(name, "name");
        final Integer getLastInt = getLastInt(name);
        return getLastInt != null ? getLastInt : defaultValue;
    }

    @Override
    @Nullable
    public Long getLong(IN_NAME name) {
        requireNonNull(name, "name");
        if (removed.contains(name)) {
            return appender != null ? appender.getLong(name) : null;
        }

        for (StringMultimapGetters<IN_NAME, NAME> delegate : delegates()) {
            final Long res = delegate.getLong(name);
            if (res != null) {
                return res;
            }
        }
        return null;
    }

    @Override
    public long getLong(IN_NAME name, long defaultValue) {
        requireNonNull(name, "name");
        final Long getLong = getLong(name);
        return getLong != null ? getLong : defaultValue;
    }

    @Override
    @Nullable
    public Long getLastLong(IN_NAME name) {
        requireNonNull(name, "name");
        if (removed.contains(name)) {
            return appender != null ? appender.getLastLong(name) : null;
        }

        Long getLastLong = null;
        for (StringMultimapGetters<IN_NAME, NAME> delegate : delegates()) {
            final Long res = delegate.getLastLong(name);
            if (res != null) {
                getLastLong = res;
            }
        }
        return getLastLong;
    }

    @Override
    public long getLastLong(IN_NAME name, long defaultValue) {
        requireNonNull(name, "name");
        final Long getLastLong = getLastLong(name);
        return getLastLong != null ? getLastLong : defaultValue;
    }

    @Override
    @Nullable
    public Float getFloat(IN_NAME name) {
        requireNonNull(name, "name");
        if (removed.contains(name)) {
            return appender != null ? appender.getFloat(name) : null;
        }

        for (StringMultimapGetters<IN_NAME, NAME> delegate : delegates()) {
            final Float res = delegate.getFloat(name);
            if (res != null) {
                return res;
            }
        }
        return null;
    }

    @Override
    public float getFloat(IN_NAME name, float defaultValue) {
        requireNonNull(name, "name");
        final Float getFloat = getFloat(name);
        return getFloat != null ? getFloat : defaultValue;
    }

    @Override
    @Nullable
    public Float getLastFloat(IN_NAME name) {
        requireNonNull(name, "name");
        if (removed.contains(name)) {
            return appender != null ? appender.getLastFloat(name) : null;
        }

        Float getLastFloat = null;
        for (StringMultimapGetters<IN_NAME, NAME> delegate : delegates()) {
            final Float res = delegate.getLastFloat(name);
            if (res != null) {
                getLastFloat = res;
            }
        }
        return getLastFloat;
    }

    @Override
    public float getLastFloat(IN_NAME name, float defaultValue) {
        requireNonNull(name, "name");
        final Float getLastFloat = getLastFloat(name);
        return getLastFloat != null ? getLastFloat : defaultValue;
    }

    @Override
    @Nullable
    public Double getDouble(IN_NAME name) {
        requireNonNull(name, "name");
        if (removed.contains(name)) {
            return appender != null ? appender.getDouble(name) : null;
        }

        for (StringMultimapGetters<IN_NAME, NAME> delegate : delegates()) {
            final Double res = delegate.getDouble(name);
            if (res != null) {
                return res;
            }
        }
        return null;
    }

    @Override
    public double getDouble(IN_NAME name, double defaultValue) {
        requireNonNull(name, "name");
        final Double getDouble = getDouble(name);
        return getDouble != null ? getDouble : defaultValue;
    }

    @Override
    @Nullable
    public Double getLastDouble(IN_NAME name) {
        requireNonNull(name, "name");
        if (removed.contains(name)) {
            return appender != null ? appender.getLastDouble(name) : null;
        }

        Double getLastDouble = null;
        for (StringMultimapGetters<IN_NAME, NAME> delegate : delegates()) {
            final Double res = delegate.getLastDouble(name);
            if (res != null) {
                getLastDouble = res;
            }
        }
        return getLastDouble;
    }

    @Override
    public double getLastDouble(IN_NAME name, double defaultValue) {
        requireNonNull(name, "name");
        final Double getLastDouble = getLastDouble(name);
        return getLastDouble != null ? getLastDouble : defaultValue;
    }

    @Override
    @Nullable
    public Long getTimeMillis(IN_NAME name) {
        requireNonNull(name, "name");
        if (removed.contains(name)) {
            return appender != null ? appender.getTimeMillis(name) : null;
        }

        for (StringMultimapGetters<IN_NAME, NAME> delegate : delegates()) {
            final Long res = delegate.getTimeMillis(name);
            if (res != null) {
                return res;
            }
        }
        return null;
    }

    @Override
    public long getTimeMillis(IN_NAME name, long defaultValue) {
        requireNonNull(name, "name");
        final Long getTimeMillis = getTimeMillis(name);
        return getTimeMillis != null ? getTimeMillis : defaultValue;
    }

    @Override
    @Nullable
    public Long getLastTimeMillis(IN_NAME name) {
        requireNonNull(name, "name");
        if (removed.contains(name)) {
            return appender != null ? appender.getLastTimeMillis(name) : null;
        }

        Long getLastTimeMillis = null;
        for (StringMultimapGetters<IN_NAME, NAME> delegate : delegates()) {
            final Long res = delegate.getLastTimeMillis(name);
            if (res != null) {
                getLastTimeMillis = res;
            }
        }
        return getLastTimeMillis;
    }

    @Override
    public long getLastTimeMillis(IN_NAME name, long defaultValue) {
        requireNonNull(name, "name");
        final Long getLastTimeMillis = getLastTimeMillis(name);
        return getLastTimeMillis != null ? getLastTimeMillis : defaultValue;
    }

    @Override
    public boolean contains(IN_NAME name) {
        requireNonNull(name, "name");
        if (removed.contains(name)) {
            return appender != null && appender.contains(name);
        }

        for (StringMultimapGetters<IN_NAME, NAME> delegate : delegates()) {
            if (delegate.contains(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean contains(IN_NAME name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        if (removed.contains(name)) {
            return appender != null && appender.contains(name, value);
        }

        return contains(name, delegate -> delegate.contains(name, value));
    }

    private boolean contains(IN_NAME name, Predicate<StringMultimapGetters<IN_NAME, NAME>> containsPredicate) {
        if (removed.contains(name)) {
            return appender != null && containsPredicate.test(appender);
        }

        boolean additionalsContain = false;
        if (additionals != null) {
            for (StringMultimapGetters<IN_NAME, NAME> getter : additionals) {
                if (containsPredicate.test(getter)) {
                    return true;
                }
                if (getter.contains(name)) {
                    additionalsContain = true;
                }
            }
        }

        boolean parentsContain = false;
        if (!additionalsContain) {
            for (StringMultimapGetters<IN_NAME, NAME> parent : parents) {
                if (containsPredicate.test(parent)) {
                    return true;
                }
                if (parent.contains(name)) {
                    parentsContain = true;
                }
            }
        }

        if (!additionalsContain && !parentsContain && defaults != null) {
            for (StringMultimapGetters<IN_NAME, NAME> getter : defaults) {
                if (containsPredicate.test(getter)) {
                    return true;
                }
            }
        }

        return appender != null ? containsPredicate.test(appender) : false;
    }

    @Override
    public boolean containsObject(IN_NAME name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        return contains(name, delegate -> delegate.containsObject(name, value));
    }

    @Override
    public boolean containsBoolean(IN_NAME name, boolean value) {
        requireNonNull(name, "name");
        return contains(name, delegate -> delegate.containsBoolean(name, value));
    }

    @Override
    public boolean containsInt(IN_NAME name, int value) {
        requireNonNull(name, "name");
        return contains(name, delegate -> delegate.containsInt(name, value));
    }

    @Override
    public boolean containsLong(IN_NAME name, long value) {
        requireNonNull(name, "name");
        return contains(name, delegate -> delegate.containsLong(name, value));
    }

    @Override
    public boolean containsFloat(IN_NAME name, float value) {
        requireNonNull(name, "name");
        return contains(name, delegate -> delegate.containsFloat(name, value));
    }

    @Override
    public boolean containsDouble(IN_NAME name, double value) {
        requireNonNull(name, "name");
        return contains(name, delegate -> delegate.containsDouble(name, value));
    }

    @Override
    public boolean containsTimeMillis(IN_NAME name, long value) {
        requireNonNull(name, "name");
        return contains(name, delegate -> delegate.containsTimeMillis(name, value));
    }

    @SuppressWarnings("unchecked")
    @Override
    public int size() {
        int size = 0;
        for (NAME name : names()) {
            size += getAll((IN_NAME) name.toString()).size();
        }
        return size;
    }

    @Override
    public boolean isEmpty() {
        if (appender != null && !appender.isEmpty()) {
            return false;
        }

        for (StringMultimapGetters<IN_NAME, NAME> delegate : delegates()) {
            for (NAME name : delegate.names()) {
                if (!removed.contains(name.toString())) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public final Set<NAME> names() {
        final ImmutableSet.Builder<NAME> builder = ImmutableSet.builder();
        if (additionals != null) {
            for (StringMultimapGetters<IN_NAME, NAME> getters : additionals) {
                for (NAME name : getters.names()) {
                    if (!removed.contains(name.toString())) {
                        builder.add(name);
                    }
                }
            }
        }

        for (StringMultimapGetters<IN_NAME, NAME> parent : parents) {
            for (NAME name : parent.names()) {
                if (!removed.contains(name.toString())) {
                    builder.add(name);
                }
            }
        }

        if (defaults != null) {
            for (StringMultimapGetters<IN_NAME, NAME> getters : defaults) {
                for (NAME name : getters.names()) {
                    if (!removed.contains(name.toString())) {
                        builder.add(name);
                    }
                }
            }
        }

        if (appender != null) {
            builder.addAll(appender.names());
        }
        return builder.build();
    }

    @Override
    public Iterator<Entry<NAME, String>> iterator() {
        Iterator<Entry<NAME, String>> additionalsIterator = null;
        if (additionals != null) {
            additionalsIterator = Iterators.concat(
                    additionals.stream()
                               .map(StringMultimapGetters::iterator)
                               .map(it -> Iterators.filter(
                                       it, entry -> !removed.contains(entry.getKey().toString())))
                               .iterator());
        }

        final Iterator<Entry<NAME, String>> parentsIterators = Iterators.concat(
                parents.stream()
                       .map(StringMultimapGetters::iterator)
                       .map(it -> Iterators.filter(it, entry -> {
                           if (removed.contains(entry.getKey().toString())) {
                               return false;
                           }
                           if (additionals != null) {
                               for (StringMultimapGetters<IN_NAME, NAME> getter : additionals) {
                                   if (getter.contains(entry.getKey())) {
                                       return false;
                                   }
                               }
                           }
                           return true;
                       }))
                       .iterator());

        Iterator<Entry<NAME, String>> defaultsIterator = null;
        if (defaults != null) {
            defaultsIterator = Iterators.concat(
                    defaults.stream()
                            .map(StringMultimapGetters::iterator)
                            .map(it -> Iterators.filter(it, entry -> {
                                if (removed.contains(entry.getKey().toString())) {
                                    return false;
                                }
                                if (additionals != null) {
                                    for (StringMultimapGetters<IN_NAME, NAME> getter : additionals) {
                                        if (getter.contains(entry.getKey())) {
                                            return false;
                                        }
                                    }
                                }
                                for (StringMultimapGetters<IN_NAME, NAME> parent : parents) {
                                    if (parent.contains(entry.getKey())) {
                                        return false;
                                    }
                                }
                                return true;
                            }))
                            .iterator());
        }

        Iterator<Entry<NAME, String>> appenderIterator = null;
        if (appender != null && !appender.isEmpty()) {
            appenderIterator = appender.iterator();
        }

        Iterator<Entry<NAME, String>> res = additionalsIterator;
        if (res == null) {
            res = parentsIterators;
        } else {
            res = Iterators.concat(res, parentsIterators);
        }

        if (defaultsIterator != null) {
            res = Iterators.concat(res, defaultsIterator);
        }

        if (appenderIterator != null) {
            res = Iterators.concat(res, appenderIterator);
        }

        return res;
    }

    @Override
    public Iterator<String> valueIterator(IN_NAME name) {
        if (removed.contains(name)) {
            return appender != null ? appender.valueIterator(name) : Collections.emptyIterator();
        }
        return getAll(name).iterator();
    }

    @Override
    public void forEach(BiConsumer<NAME, String> action) {
        requireNonNull(action, "action");
        for (Entry<NAME, String> entry : this) {
            action.accept(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void forEachValue(IN_NAME name, Consumer<String> action) {
        requireNonNull(name, "name");
        requireNonNull(action, "action");
        if (removed.contains(name)) {
            if (appender != null) {
                appender.forEachValue(name, action);
            }
            return;
        }

        for (String value : getAll(name)) {
            action.accept(value);
        }
    }

    // Mutators

    @Nullable
    final String getAndRemove(IN_NAME name) {
        requireNonNull(name, "name");
        final String get = get(name);
        remove0(name);
        return get;
    }

    final String getAndRemove(IN_NAME name, String defaultValue) {
        requireNonNull(defaultValue, "defaultValue");
        final String value = getAndRemove(name);
        return value != null ? value : defaultValue;
    }

    final List<String> getAllAndRemove(IN_NAME name) {
        requireNonNull(name, "name");
        final List<String> getAll = getAll(name);
        remove0(name);
        return getAll;
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
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        appender().add(name, value);
    }

    final void add(IN_NAME name, Iterable<String> values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        appender().add(name, values);
    }

    final void add(IN_NAME name, String... values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        appender().add(name, values);
    }

    final void add(Iterable<? extends Map.Entry<? extends IN_NAME, String>> entries) {
        requireNonNull(entries, "entries");
        appender().add(entries);
    }

    final void addObject(IN_NAME name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        appender().addObject(name, value);
    }

    final void addObject(IN_NAME name, Iterable<?> values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        appender().addObject(name, values);
    }

    final void addObject(IN_NAME name, Object... values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        appender().addObject(name, values);
    }

    void addObject(Iterable<? extends Map.Entry<? extends IN_NAME, ?>> entries) {
        requireNonNull(entries, "entries");
        appender().addObject(entries);
    }

    final void addInt(IN_NAME name, int value) {
        requireNonNull(name, "name");
        appender().addInt(name, value);
    }

    final void addLong(IN_NAME name, long value) {
        requireNonNull(name, "name");
        appender().addLong(name, value);
    }

    final void addFloat(IN_NAME name, float value) {
        requireNonNull(name, "name");
        appender().addFloat(name, value);
    }

    final void addDouble(IN_NAME name, double value) {
        requireNonNull(name, "name");
        appender().addDouble(name, value);
    }

    final void addTimeMillis(IN_NAME name, long value) {
        requireNonNull(name, "name");
        appender().addTimeMillis(name, value);
    }

    final void set(IN_NAME name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        removeAndAppender(name, appender -> appender.set(name, value));
    }

    final void set(IN_NAME name, Iterable<String> values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        removeAndAppender(name, appender -> appender.set(name, values));
    }

    final void set(IN_NAME name, String... values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        removeAndAppender(name, appender -> appender.set(name, values));
    }

    final void set(Iterable<? extends Map.Entry<? extends IN_NAME, String>> entries) {
        requireNonNull(entries, "entries");
        removeAndAppender(entries, appender -> appender.set(entries));
    }

    final CompositeStringMultimap<IN_NAME, NAME> setIfAbsent(
            Iterable<? extends Map.Entry<? extends IN_NAME, String>> entries) {
        requireNonNull(entries, "entries");
        final ImmutableListMultimap.Builder<IN_NAME, Map.Entry<? extends IN_NAME, String>> builder =
                ImmutableListMultimap.builder();
        entries.forEach(entry -> builder.put(entry.getKey(), entry));

        final ImmutableListMultimap<IN_NAME, Map.Entry<? extends IN_NAME, String>> map = builder.build();
        for (IN_NAME name : map.keySet()) {
            if (!contains(name)) {
                removeAndAppender(name, appender -> appender.add(map.get(name)));
            }
        }

        return this;
    }

    final void setObject(IN_NAME name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        removeAndAppender(name, appender -> appender.setObject(name, value));
    }

    final void setObject(IN_NAME name, Iterable<?> values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        removeAndAppender(name, appender -> appender.setObject(name, values));
    }

    final void setObject(IN_NAME name, Object... values) {
        requireNonNull(name, "name");
        requireNonNull(values, "values");
        removeAndAppender(name, appender -> appender.setObject(name, values));
    }

    final void setObject(Iterable<? extends Map.Entry<? extends IN_NAME, ?>> entries) {
        requireNonNull(entries, "entries");
        removeAndAppender(entries, appender -> appender.setObject(entries));
    }

    final void setInt(IN_NAME name, int value) {
        requireNonNull(name, "name");
        removeAndAppender(name, appender -> appender.setInt(name, value));
    }

    final void setLong(IN_NAME name, long value) {
        requireNonNull(name, "name");
        removeAndAppender(name, appender -> appender.setLong(name, value));
    }

    final void setFloat(IN_NAME name, float value) {
        requireNonNull(name, "name");
        removeAndAppender(name, appender -> appender.setFloat(name, value));
    }

    final void setDouble(IN_NAME name, double value) {
        requireNonNull(name, "name");
        removeAndAppender(name, appender -> appender.setDouble(name, value));
    }

    final void setTimeMillis(IN_NAME name, long value) {
        requireNonNull(name, "name");
        removeAndAppender(name, appender -> appender.setTimeMillis(name, value));
    }

    final boolean remove(IN_NAME name) {
        requireNonNull(name, "name");
        final boolean remove = contains(name);
        remove0(name);
        return remove;
    }

     protected final void remove0(IN_NAME name) {
        removed.add(name);
        if (appender != null && appender.contains(name)) {
            appender.remove(name);
        }
    }

    private void removeAndAppender(IN_NAME name, Consumer<StringMultimap<IN_NAME, NAME>> appenderConsumer) {
        remove0(name);
        appenderConsumer.accept(appender());
    }

    private void removeAndAppender(Iterable<? extends Map.Entry<? extends IN_NAME, ?>> entries,
                                   Consumer<StringMultimap<IN_NAME, NAME>> appenderConsumer) {
        for (Map.Entry<? extends IN_NAME, ?> e : entries) {
            remove0(e.getKey());
        }
        appenderConsumer.accept(appender());
    }

    final void clear() {
        if (additionals != null) {
            for (StringMultimap<IN_NAME, NAME> multimap : additionals) {
                multimap.clear();
            }
        }

        for (StringMultimap<IN_NAME, NAME> parent : parents) {
            parent.clear();
        }

        if (defaults != null) {
            for (StringMultimap<IN_NAME, NAME> multimap : defaults) {
                multimap.clear();
            }
        }

        if (appender != null) {
            appender.clear();
        }

        removed.clear();
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

    // hashCode(), equals() and toString()

    @Override
    public int hashCode() {
        int result = HASH_CODE_SEED;
        int hashCode;
        if (additionals != null) {
            hashCode = additionals.hashCode();
            result = (result * 31 + hashCode) * 31 + hashCode;
        }

        for (StringMultimap<IN_NAME, NAME> parent : parents) {
            hashCode = parent.hashCode();
            result = (result * 31 + hashCode) * 31 + hashCode;
        }

        if (defaults != null) {
            hashCode = defaults.hashCode();
            result = (result * 31 + hashCode) * 31 + hashCode;
        }

        if (appender != null) {
            hashCode = appender.hashCode();
            result = (result * 31 + hashCode) * 31 + hashCode;
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof StringMultimapGetters)) {
            return false;
        }

        final StringMultimapGetters<IN_NAME, NAME> that = (StringMultimapGetters<IN_NAME, NAME>) o;
        if (size() != that.size()) {
            return false;
        }

        if (that instanceof CompositeStringMultimap ||
            that instanceof StringMultimap) {
            for (NAME name : names()) {
                if (!getAll((IN_NAME) name.toString())
                        .equals(that.getAll((IN_NAME) name.toString()))) {
                    return false;
                }
            }
        } else {
            for (NAME name : names()) {
                if (!Iterators.elementsEqual(valueIterator((IN_NAME) name.toString()),
                                             that.valueIterator((IN_NAME) name.toString()))) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public String toString() {
        final int size = size();
        if (size == 0) {
            return "[]";
        }

        final StringBuilder sb = new StringBuilder(7 + size * 20);
        sb.append('[');

        for (final Entry<NAME, String> cur : this) {
            sb.append(cur.getKey()).append('=').append(cur.getValue()).append(", ");
        }

        final int length = sb.length();
        sb.setCharAt(length - 2, ']');
        return sb.substring(0, length - 1);
    }
}

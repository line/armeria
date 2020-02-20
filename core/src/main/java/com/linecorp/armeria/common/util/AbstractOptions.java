/*
 * Copyright 2015 LINE Corporation
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
package com.linecorp.armeria.common.util;

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Iterators;

/**
 * A set of configuration options and their respective values.
 *
 * @see AbstractOption
 * @see AbstractOptionValue
 */
public abstract class AbstractOptions<T extends AbstractOptionValue<?, ?>> implements Iterable<T> {

    /**
     * Returns the value of the specified {@code option} in the {@code first}.
     * If the {@code option} is not found in {@code first}, will look for the {@code second}.
     *
     * @param <O> the type of the option value
     * @param <V> the type of the value
     * @return the value of the specified {@link AbstractOption}
     * @throws NoSuchElementException if the specified {@link AbstractOption} does not have a value.
     */
    protected static <O extends AbstractOptionValue<?, ?>, V>
    V get(AbstractOptions<O> first, AbstractOptions<O> second, AbstractOption<V> option) {
        final V value = first.getOrNull0(option);
        if (value != null) {
            return value;
        }
        return second.get0(option);
    }

    /**
     * Returns the value of the specified {@code option} in the {@code first}.
     * If the {@code option} is not found in {@code first}, will look for the {@code second}.
     *
     * @param <O> the type of the option value
     * @param <V> the type of the value
     * @return the value of the {@link AbstractOption}, or
     *         {@code null} if the specified {@link AbstractOption} is not set.
     */
    @Nullable
    protected static <O extends AbstractOptionValue<?, ?>, V>
    V getOrNull(AbstractOptions<O> first, AbstractOptions<O> second, AbstractOption<V> option) {
        final V value = first.getOrNull0(option);
        if (value != null) {
            return value;
        }
        return second.getOrNull0(option);
    }

    /**
     * Returns the value of the specified {@code option} in the {@code first}.
     * If the {@code option} is not found in {@code first}, will look for the {@code second}.
     *
     * @param <O> the type of the option value
     * @param <V> the type of the value
     * @return the value of the {@link AbstractOption}, or
     *         {@code defaultValue} if the specified {@link AbstractOption} is not set.
     */
    protected static <O extends AbstractOptionValue<?, ?>, V>
    V getOrElse(AbstractOptions<O> first, AbstractOptions<O> second, AbstractOption<V> option, V defaultValue) {
        final V value = getOrNull(first, second, option);
        if (value != null) {
            return value;
        } else {
            return defaultValue;
        }
    }

    private final Map<AbstractOption<T>, T> valueMap;

    /**
     * Creates a new instance.
     *
     * @param values the option values
     */
    @SafeVarargs
    protected AbstractOptions(T... values) {
        requireNonNull(values, "values");

        valueMap = new IdentityHashMap<>();
        putAll(Arrays.asList(values));
    }

    /**
     * Creates a new instance.
     *
     * @param values the option values
     */
    protected AbstractOptions(Iterable<T> values) {
        requireNonNull(values, "values");

        valueMap = new IdentityHashMap<>();
        putAll(values);
    }

    /**
     * Creates a new instance.
     *
     * @param baseOptions the base options to merge
     * @param additionalValues the additional option values
     */
    @SafeVarargs
    protected AbstractOptions(AbstractOptions<T> baseOptions, T... additionalValues) {
        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(additionalValues, "additionalValues");

        valueMap = new IdentityHashMap<>(baseOptions.valueMap);
        putAll(Arrays.asList(additionalValues));
    }

    /**
     * Creates a new instance.
     *
     * @param baseOptions the base options to merge
     * @param additionalValues the option values
     */
    protected AbstractOptions(AbstractOptions<T> baseOptions, Iterable<T> additionalValues) {
        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(additionalValues, "additionalValues");

        valueMap = new IdentityHashMap<>(baseOptions.valueMap);
        putAll(additionalValues);
    }

    /**
     * Creates a new instance by merging two options.
     *
     * @param baseOptions the base options to merge
     * @param additionalOptions the additional options to merge
     */
    protected AbstractOptions(AbstractOptions<T> baseOptions, AbstractOptions<T> additionalOptions) {

        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(additionalOptions, "additionalOptions");

        valueMap = new IdentityHashMap<>(baseOptions.valueMap);
        putAll(additionalOptions.valueMap.values());
    }

    /**
     * Filters an {@link AbstractOptionValue}. You can apply this filter before creating a new options.
     */
    protected abstract T filterValue(T value);

    /**
     * Merge two option values. You can specify how to merge conflict values.
     *
     * <p>For example:
     * <pre>{@code
     * // keep old value
     * protected <T extends AbstractOptionValue<?, ?>> T mergeValue(T oldValue, T newValue) {
     *     return oldValue;
     * }
     *
     * // override old value
     * protected <T extends AbstractOptionValue<?, ?>> T mergeValue(T oldValue, T newValue) {
     *     return newValue;
     * }
     * }</pre>
     *
     * @param oldValue an option value which was set before.
     * @param newValue a new option value.
     */
    protected abstract T mergeValue(T oldValue, T newValue);

    private void putAll(Iterable<T> values) {
        for (final T value : values) {
            final T newValue = filterValue(value);
            @SuppressWarnings("unchecked")
            final AbstractOption<T> option = (AbstractOption<T>) newValue.option();
            valueMap.compute(option,
                             (k, oldValue) -> oldValue == null ? newValue : mergeValue(oldValue, newValue));
        }
    }

    /**
     * Returns the value of the specified {@code option}.
     *
     * @param <O> the type of the option
     * @param <V> the type of the value
     *
     * @throws NoSuchElementException if the specified {@code option} does not have a value.
     */
    protected final <O extends AbstractOption<V>, V> V get0(AbstractOption<V> option) {
        @SuppressWarnings("unchecked")
        final AbstractOptionValue<O, V> optionValue = (AbstractOptionValue<O, V>) valueMap.get(option);
        if (optionValue == null) {
            throw new NoSuchElementException();
        }
        return optionValue.value();
    }

    /**
     * Returns the value of the specified {@code option}.
     *
     * @param <O> the type of the option
     * @param <V> the type of the value
     */
    @Nullable
    protected final <O extends AbstractOption<V>, V> V getOrNull0(AbstractOption<V> option) {
        @SuppressWarnings("unchecked")
        final AbstractOptionValue<O, V> optionValue = (AbstractOptionValue<O, V>) valueMap.get(option);
        return optionValue != null ? optionValue.value() : null;
    }

    @Override
    public Iterator<T> iterator() {
        return Iterators.unmodifiableIterator(valueMap.values().iterator());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("values", valueMap.values())
                          .toString();
    }
}

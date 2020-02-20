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
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;

/**
 * A set of configuration options and their respective values.
 *
 * @see AbstractOption
 * @see AbstractOptionValue
 */
public abstract class AbstractOptions {

    protected static <V> V get(AbstractOptions first, AbstractOptions second, AbstractOption<V> option) {
        final V value = first.getOrNull0(option);
        if (value != null) {
            return value;
        }
        return second.get0(option);
    }

    @Nullable
    protected static <T> T getOrNull(AbstractOptions first, AbstractOptions second, AbstractOption<T> option) {
        final T value = first.getOrNull0(option);
        if (value != null) {
            return value;
        }
        return second.getOrNull0(option);
    }

    protected static <T> T getOrElse(AbstractOptions first, AbstractOptions second,
                                     AbstractOption<T> option, T defaultValue) {
        final T value = getOrNull(first, second, option);
        if (value != null) {
            return value;
        } else {
            return defaultValue;
        }
    }

    private final Map<AbstractOption<Object>, AbstractOptionValue<AbstractOption<Object>, Object>> valueMap;

    /**
     * Creates a new instance.
     *
     * @param <T> the type of the {@link AbstractOptionValue}
     * @param values the option values
     */
    @SafeVarargs
    protected <T extends AbstractOptionValue<?, ?>> AbstractOptions(T... values) {
        requireNonNull(values, "values");

        valueMap = new IdentityHashMap<>();
        putAll(Arrays.asList(values));
    }

    /**
     * Creates a new instance.
     *
     * @param <T> the type of the {@link AbstractOptionValue}
     * @param values the option values
     */
    protected <T extends AbstractOptionValue<?, ?>> AbstractOptions(Iterable<T> values) {
        requireNonNull(values, "values");

        valueMap = new IdentityHashMap<>();
        putAll(values);
    }

    /**
     * Creates a new instance.
     *
     * @param <T> the type of the {@link AbstractOptionValue}
     * @param baseOptions the base options to merge
     * @param additionalValues the additional option values
     */
    @SafeVarargs
    protected <T extends AbstractOptionValue<?, ?>> AbstractOptions(AbstractOptions baseOptions,
                                                                    T... additionalValues) {
        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(additionalValues, "additionalValues");

        valueMap = new IdentityHashMap<>(baseOptions.valueMap);
        putAll(Arrays.asList(additionalValues));
    }

    /**
     * Creates a new instance.
     *
     * @param <T> the type of the {@link AbstractOptionValue}
     * @param baseOptions the base options to merge
     * @param additionalValues the option values
     */
    protected <T extends AbstractOptionValue<?, ?>> AbstractOptions(AbstractOptions baseOptions,
                                                                    Iterable<T> additionalValues) {
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
    protected AbstractOptions(AbstractOptions baseOptions, AbstractOptions additionalOptions) {

        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(additionalOptions, "additionalOptions");

        valueMap = new IdentityHashMap<>(baseOptions.valueMap);
        putAll(additionalOptions.valueMap.values());
    }

    /**
     * Filters an {@link AbstractOptionValue}. You can apply this filter before creating a new options.
     */
    protected abstract <T extends AbstractOptionValue<?, ?>> T filterValue(T value);

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
     * @param <T> the type of the {@link AbstractOptionValue}
     * @param oldValue an option value which was set before.
     * @param newValue a new option value.
     */
    protected abstract <T extends AbstractOptionValue<?, ?>> T mergeValue(T oldValue, T newValue);

    @SuppressWarnings("unchecked")
    private <T extends AbstractOptionValue<?, ?>> void putAll(Iterable<T> values) {
        for (final T value : values) {
            final T newValue = filterValue(value);
            final AbstractOption<Object> option = (AbstractOption<Object>) newValue.option();
            final AbstractOptionValue<AbstractOption<Object>, Object> oldValue = valueMap.get(option);

            if (oldValue == null) {
                final AbstractOptionValue<AbstractOption<Object>, Object> optionValue =
                        (AbstractOptionValue<AbstractOption<Object>, Object>) newValue;
                valueMap.put(option, optionValue);
            } else {
                final AbstractOptionValue<AbstractOption<Object>, Object> merged =
                        (AbstractOptionValue<AbstractOption<Object>, Object>)
                                mergeValue(oldValue, newValue);
                valueMap.put(option, merged);
            }
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

    /**
     * Returns the value of the specified {@code option}.
     *
     * @param <O> the type of the option
     * @param <V> the type of the value
     * @return the value of the specified {@code option}. {@code defaultValue} if there's no such option.
     */
    protected final <O extends AbstractOption<V>, V> V getOrElse0(O option, V defaultValue) {
        requireNonNull(defaultValue, "defaultValue");
        final V value = getOrNull0(option);
        if (value != null) {
            return value;
        } else {
            return defaultValue;
        }
    }

    /**
     * Returns {@link AbstractOption}s of this {@link AbstractOptions}.
     */
    protected final Set<? extends AbstractOption<?>> options0() {
        return Collections.unmodifiableSet(valueMap.keySet());
    }

    @Override
    public String toString() {
        return toString(valueMap.values());
    }

    static String toString(Collection<?> values) {
        return "OptionValues{" + values + '}';
    }
}

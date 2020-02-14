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

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.google.common.collect.Streams;

/**
 * A set of configuration options and their respective values.
 *
 * @see AbstractOption
 * @see AbstractOptionValue
 */
public abstract class AbstractOptions {

    private final Map<AbstractOption<Object>, AbstractOptionValue<AbstractOption<Object>, Object>> valueMap;

    /**
     * Creates a new instance.
     *
     * @param <T> the type of the {@link AbstractOptionValue}
     * @param valueFilter the {@link Function} to apply to the elements of the specified {@code values}
     * @param values the option values
     */
    @SafeVarargs
    protected <T extends AbstractOptionValue<?, ?>> AbstractOptions(Function<T, T> valueFilter, T... values) {
        requireNonNull(valueFilter, "valueFilter");
        requireNonNull(values, "values");

        valueMap = new IdentityHashMap<>();
        putAll(valueFilter, Stream.of(values));
    }

    /**
     * Creates a new instance.
     *
     * @param <T> the type of the {@link AbstractOptionValue}
     * @param valueFilter the {@link Function} to apply to the elements of the specified {@code values}
     * @param values the option values
     */
    protected <T extends AbstractOptionValue<?, ?>> AbstractOptions(Function<T, T> valueFilter,
                                                                    Iterable<T> values) {
        requireNonNull(valueFilter, "valueFilter");
        requireNonNull(values, "values");

        valueMap = new IdentityHashMap<>();
        putAll(valueFilter, Streams.stream(values));
    }

    /**
     * Creates a new instance.
     *
     * @param <T> the type of the {@link AbstractOptionValue}
     * @param valueFilter the {@link Function} to apply to the elements of the specified
     *                    {@code additionalValues}
     * @param baseOptions the base options to merge
     * @param additionalValues the additional option values
     */
    @SafeVarargs
    protected <T extends AbstractOptionValue<?, ?>> AbstractOptions(Function<T, T> valueFilter,
                                                                    AbstractOptions baseOptions,
                                                                    T... additionalValues) {
        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(valueFilter, "valueFilter");
        requireNonNull(additionalValues, "additionalValues");

        valueMap = new IdentityHashMap<>(baseOptions.valueMap);
        putAll(valueFilter, Stream.of(additionalValues));
    }

    /**
     * Creates a new instance.
     *
     * @param <T> the type of the {@link AbstractOptionValue}
     * @param valueFilter the {@link Function} to apply to the elements of the specified
     *                    {@code additionalValues}
     * @param baseOptions the base options to merge
     * @param additionalValues the option values
     */
    protected <T extends AbstractOptionValue<?, ?>> AbstractOptions(Function<T, T> valueFilter,
                                                                    AbstractOptions baseOptions,
                                                                    Iterable<T> additionalValues) {
        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(valueFilter, "valueFilter");
        requireNonNull(additionalValues, "additionalValues");

        valueMap = new IdentityHashMap<>(baseOptions.valueMap);
        putAll(valueFilter, Streams.stream(additionalValues));
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
        valueMap.putAll(additionalOptions.valueMap);
    }

    @SuppressWarnings("unchecked")
    private <T extends AbstractOptionValue<?, ?>> void putAll(Function<T, T> valueFilter, Stream<T> values) {
        values.map(valueFilter)
              .forEach(v -> valueMap.put((AbstractOption<Object>) v.option(),
                                         (AbstractOptionValue<AbstractOption<Object>, Object>) v));
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
     * Returns the {@link Map} whose key is {@link AbstractOption} and value is {@link AbstractOptionValue}.
     *
     * @param <K> the type of the options
     * @param <V> the type of the option values
     */
    @SuppressWarnings("unchecked")
    protected final <K extends AbstractOption<?>, V extends AbstractOptionValue<K, ?>> Map<K, V> asMap0() {
        return Collections.unmodifiableMap((Map<? extends K, ? extends V>) valueMap);
    }

    @Override
    public String toString() {
        return toString(asMap0().values());
    }

    static String toString(Collection<?> values) {
        return "OptionValues{" + values + '}';
    }
}

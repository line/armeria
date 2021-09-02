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

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

import com.google.common.collect.Iterators;

import com.linecorp.armeria.common.annotation.Nullable;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;

/**
 * A set of configuration options and their respective values.
 *
 * @param <T> the type of the option.
 * @param <U> the type of the option value holder.
 *
 * @see AbstractOption
 * @see AbstractOptionValue
 */
public abstract class AbstractOptions<
        T extends AbstractOption<T, U, Object>,
        U extends AbstractOptionValue<U, T, Object>> implements Iterable<U> {

    private final Reference2ReferenceOpenHashMap<T, U> valueMap;

    /**
     * Creates a new instance.
     *
     * @param values the option values
     */
    protected AbstractOptions(Iterable<? extends AbstractOptionValue<?, ?, ?>> values) {
        requireNonNull(values, "values");
        valueMap = init(values);
    }

    /**
     * Creates a new instance.
     *
     * @param baseOptions the base options to merge
     * @param additionalValues the option values
     */
    protected AbstractOptions(AbstractOptions<T, U> baseOptions,
                              Iterable<? extends AbstractOptionValue<?, ?, ?>> additionalValues) {
        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(additionalValues, "additionalValues");

        if (baseOptions.valueMap.isEmpty()) {
            valueMap = init(additionalValues);
        } else {
            valueMap = init(baseOptions, additionalValues);
        }
    }

    private Reference2ReferenceOpenHashMap<T, U> init(
            Iterable<? extends AbstractOptionValue<?, ?, ?>> additionalValues) {

        if (additionalValues instanceof AbstractOptions) {
            @SuppressWarnings("unchecked")
            final Reference2ReferenceOpenHashMap<T, U> clone =
                    ((AbstractOptions<T, U>) additionalValues).valueMap.clone();
            return clone;
        }

        final Reference2ReferenceOpenHashMap<T, U> map = new Reference2ReferenceOpenHashMap<>();
        for (final AbstractOptionValue<?, ?, ?> value : additionalValues) {
            @SuppressWarnings("unchecked")
            final U newValue = (U) value;
            map.put(newValue.option(), newValue);
        }
        return map;
    }

    private Reference2ReferenceOpenHashMap<T, U> init(
            AbstractOptions<T, U> baseOptions,
            Iterable<? extends AbstractOptionValue<?, ?, ?>> additionalValues) {

        // Use cheaper Iterable if possible.
        if (additionalValues instanceof AbstractOptions) {
            @SuppressWarnings("unchecked")
            final Reference2ReferenceOpenHashMap<T, U> additionalValueMap =
                    ((AbstractOptions<T, U>) additionalValues).valueMap;
            additionalValues = additionalValueMap.values();
        }

        // Merge all options.
        final Reference2ReferenceOpenHashMap<T, U> map = baseOptions.valueMap.clone();
        for (final AbstractOptionValue<?, ?, ?> value : additionalValues) {
            @SuppressWarnings("unchecked")
            final U newValue = (U) value;
            final T option = newValue.option();
            @Nullable final U oldValue = map.putIfAbsent(option, newValue);
            if (oldValue != null) {
                map.put(option, option.merge(oldValue, newValue));
            }
        }
        return map;
    }

    /**
     * Returns the value of the specified {@code option}.
     *
     * @param <V> the type of the value
     */
    public final <V> V get(AbstractOption<?, ?, V> option) {
        requireNonNull(option, "option");
        @Nullable final U optionValue = valueMap.get(option);
        if (optionValue == null) {
            return option.defaultValue();
        }

        @SuppressWarnings("unchecked")
        final V cast = (V) optionValue.value();
        return cast;
    }

    /**
     * Returns an immutable {@link Iterator} of user-specified options.
     */
    @Override
    public final Iterator<U> iterator() {
        return Iterators.unmodifiableIterator(valueMap.values().iterator());
    }

    /**
     * Returns an immutable {@link Map} of user-specified options.
     */
    public final Map<T, U> asMap() {
        return Collections.unmodifiableMap(valueMap);
    }

    @Override
    public final String toString() {
        return getClass().getSimpleName() + Iterators.toString(valueMap.values().iterator());
    }
}

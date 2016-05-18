/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A set of configuration options and their respective values.
 *
 * @see AbstractOption
 * @see AbstractOptionValue
 */
public abstract class AbstractOptions {

    protected Map<AbstractOption<Object>, AbstractOptionValue<AbstractOption<Object>, Object>> valueMap;

    @SafeVarargs
    protected <T extends AbstractOptionValue<?, ?>> AbstractOptions(Function<T, T> valueFilter, T... values) {
        requireNonNull(valueFilter, "valueFilter");
        requireNonNull(values, "values");

        valueMap = new IdentityHashMap<>();
        putAll(valueFilter, Stream.of(values));
    }

    protected <T extends AbstractOptionValue<?, ?>> AbstractOptions(Function<T, T> valueFilter,
                                                                    Iterable<T> values) {

        requireNonNull(valueFilter, "valueFilter");
        requireNonNull(values, "values");

        valueMap = new IdentityHashMap<>();
        putAll(valueFilter, StreamSupport.stream(values.spliterator(), false));
    }

    @SafeVarargs
    protected <T extends AbstractOptionValue<?, ?>> AbstractOptions(Function<T, T> valueFilter,
                                                                    AbstractOptions baseOptions, T... values) {

        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(valueFilter, "valueFilter");
        requireNonNull(values, "values");

        valueMap = new IdentityHashMap<>(baseOptions.valueMap);
        putAll(valueFilter, Stream.of(values));
    }

    protected <T extends AbstractOptionValue<?, ?>> AbstractOptions(Function<T, T> valueFilter,
                                                                    AbstractOptions baseOptions,
                                                                    Iterable<T> values) {

        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(valueFilter, "valueFilter");
        requireNonNull(values, "values");

        valueMap = new IdentityHashMap<>(baseOptions.valueMap);
        putAll(valueFilter, StreamSupport.stream(values.spliterator(), false));
    }

    protected <T extends AbstractOptionValue<?, ?>> AbstractOptions(AbstractOptions baseOptions,
                                                                    AbstractOptions options) {

        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(options, "options");

        valueMap = new IdentityHashMap<>(baseOptions.valueMap);
        valueMap.putAll(options.valueMap);
    }

    @SuppressWarnings("unchecked")
    private <T extends AbstractOptionValue<?, ?>> void putAll(Function<T, T> valueFilter, Stream<T> values) {
        values.map(valueFilter)
              .forEach(v -> valueMap.put((AbstractOption<Object>) v.option(),
                                         (AbstractOptionValue<AbstractOption<Object>, Object>) v));
    }

    @SuppressWarnings("unchecked")
    protected <O extends AbstractOption<V>, V> Optional<V> get0(AbstractOption<V> option) {
        @SuppressWarnings("rawtypes")
        AbstractOptionValue<O, V> optionValue =
                (AbstractOptionValue<O, V>) (AbstractOptionValue) valueMap.get(option);
        return optionValue == null ? Optional.empty() : Optional.of(optionValue.value());
    }

    @SuppressWarnings("unchecked")
    protected <O extends AbstractOption<V>, V> V getOrElse0(O option, V defaultValue) {
        return get0(option).orElse(defaultValue);
    }

    @SuppressWarnings("unchecked")
    protected <K, V> Map<K, V> asMap0() {
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

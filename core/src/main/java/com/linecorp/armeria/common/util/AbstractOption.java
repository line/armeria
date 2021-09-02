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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A configuration option.
 *
 * @param <T> the type of the option.
 * @param <U> the type of the option value holder.
 * @param <V> the type of the option value.
 *
 * @see AbstractOptionValue
 * @see AbstractOptions
 */
public abstract class AbstractOption<
        T extends AbstractOption<T, U, V>,
        U extends AbstractOptionValue<U, T, V>,
        V> implements Comparable<AbstractOption<?, ?, ?>> {

    private static final AtomicLong uniqueIdGenerator = new AtomicLong();

    private static final ClassValue<Pool> map = new ClassValue<Pool>() {
        @Override
        protected Pool computeValue(Class<?> type) {
            return new Pool(type);
        }
    };

    /**
     * Returns all available options of the specified option type.
     *
     * @return the options which are instances of the specified {@code type}.
     */
    protected static <T extends Set<?>> T allOptions(Class<?> type) {
        requireNonNull(type, "type");
        @Nullable final Pool pool = map.get(type);
        if (pool == null) {
            @SuppressWarnings("unchecked")
            final T cast = (T) ImmutableSet.of();
            return cast;
        }

        @SuppressWarnings("unchecked")
        final T cast = (T) pool.getAll();
        return cast;
    }

    /**
     * Returns the option of the specified option type and name.
     *
     * @return the option which is an instance of the specified {@code type} and
     *         which has the specified {@code name}.
     */
    protected static <T extends AbstractOption<?, ?, ?>> T of(Class<?> type, String name) {
        requireNonNull(type, "type");
        requireNonNull(name, "name");

        @Nullable final Pool pool = map.get(type);
        if (pool == null) {
            throw new NoSuchElementException(
                    '\'' + type.getName() + '#' + name + "' does not exist.");
        }

        @SuppressWarnings("unchecked")
        final T cast = (T) pool.get(name);
        return cast;
    }

    /**
     * Defines a new option.
     *
     * @param type the type of the option, e.g. {@link ClientOption}.
     * @param name the name of the option, e.g. {@code "RESPONSE_TIMEOUT_MILLIS"}.
     * @param defaultValue the default value of the option.
     * @param optionFactory the {@link Factory} that creates a new option.
     * @param validator the {@link Function} which is used for validating ane normalizing an option value.
     * @param mergeFunction the {@link BiFunction} which is used for merging old and new option values.
     * @param <T> the type of the option.
     * @param <U> the type of the option value holder.
     * @param <V> the type of the option value.
     *
     * @return a new option instance.
     *
     * @throws IllegalStateException if an option with the specified name exists already.
     */
    protected static <T extends AbstractOption<T, U, V>, U extends AbstractOptionValue<U, T, V>, V>
    T define(Class<?> type, String name, V defaultValue,
             Factory<T, U, V> optionFactory, Function<V, V> validator, BiFunction<U, U, U> mergeFunction) {

        requireNonNull(type, "type");
        requireNonNull(name, "name");
        requireNonNull(defaultValue, "defaultValue");
        requireNonNull(optionFactory, "optionFactory");
        requireNonNull(validator, "validator");
        requireNonNull(mergeFunction, "mergeFunction");

        return map.get(type)
                  .define(optionFactory, name, defaultValue, validator, mergeFunction);
    }

    private final long uniqueId;
    private final String name;
    private final V defaultValue;
    private final Function<V, V> validator;
    private final BiFunction<U, U, U> mergeFunction;

    /**
     * Creates a new instance.
     *
     * @param name the name of this option
     */
    protected AbstractOption(String name, V defaultValue,
                             Function<V, V> validator, BiFunction<U, U, U> mergeFunction) {
        uniqueId = uniqueIdGenerator.getAndIncrement();
        this.name = requireNonNull(name, "name");
        this.defaultValue = requireNonNull(defaultValue, "defaultValue");
        this.validator = requireNonNull(validator, "validator");
        this.mergeFunction = requireNonNull(mergeFunction, "mergeFunction");
    }

    /**
     * Returns the name of this option.
     */
    public final String name() {
        return name;
    }

    /**
     * Returns the default value of this option.
     */
    public final V defaultValue() {
        return defaultValue;
    }

    /**
     * Merges the specified new option value into the specified old option value.
     *
     * @param oldValue the old option value.
     * @param newValue the new option value.
     * @return the merged option value.
     */
    final U merge(U oldValue, U newValue) {
        requireNonNull(oldValue, "oldValue");
        requireNonNull(newValue, "newValue");
        final U merged = mergeFunction.apply(oldValue, newValue);
        checkState(merged != null, "mergeFunction must not return null: %s, %s, %s", this, oldValue, newValue);
        return merged;
    }

    /**
     * Returns a newly created option value.
     */
    public final U newValue(V value) {
        requireNonNull(value, "value");
        value = validator.apply(value);
        requireNonNull(value, "validator must not return null.");
        return doNewValue(value);
    }

    /**
     * Implement this method to return a new option value.
     */
    protected abstract U doNewValue(V value);

    @Override
    public final int hashCode() {
        return super.hashCode();
    }

    @Override
    public final int compareTo(AbstractOption<?, ?, ?> o) {
        return Long.compare(uniqueId, o.uniqueId);
    }

    @Override
    public final boolean equals(Object obj) {
        return super.equals(obj);
    }

    @Override
    public final String toString() {
        return name();
    }

    /**
     * Creates a new option instance.
     *
     * @param <T> the type of the option.
     * @param <U> the type of the option value holder.
     * @param <V> the type of the option value.
     *
     * @see #define(Class, String, Object, Factory, Function, BiFunction)
     */
    @FunctionalInterface
    protected interface Factory<T extends AbstractOption<T, U, V>,
            U extends AbstractOptionValue<U, T, V>,
            V> {
        /**
         * Returns a newly created option with the specified properties.
         */
        T get(String name, V defaultValue,
              Function<V, V> validator, BiFunction<U, U, U> mergeFunction);
    }

    private static final class Pool {

        private final Class<?> type;
        private final BiMap<String, AbstractOption<?, ?, ?>> options;

        Pool(Class<?> type) {
            this.type = type;
            options = HashBiMap.create();
        }

        synchronized <T extends AbstractOption<T, U, V>, U extends AbstractOptionValue<U, T, V>, V>
        T define(Factory<T, U, V> optionFactory, String name, V defaultValue,
                 Function<V, V> validator, BiFunction<U, U, U> mergeFunction) {
            final AbstractOption<?, ?, ?> oldOption = options.get(name);
            if (oldOption != null) {
                throw new IllegalStateException(
                        '\'' + type.getName() + '#' + name + "' exists already.");
            }

            final T newOption = optionFactory.get(name, defaultValue, validator, mergeFunction);
            checkArgument(type.isInstance(newOption),
                          "OptionFactory.newOption() must return an instance of %s.", type);
            options.put(name, newOption);
            return newOption;
        }

        synchronized AbstractOption<?, ?, ?> get(String name) {
            final AbstractOption<?, ?, ?> option = options.get(name);
            if (option == null) {
                throw new NoSuchElementException(
                        '\'' + type.getName() + '#' + name + "' does not exist.");
            }

            return option;
        }

        synchronized Set<AbstractOption<?, ?, ?>> getAll() {
            return ImmutableSet.copyOf(options.values());
        }
    }
}

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

package com.linecorp.armeria.client;

import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.common.util.AbstractOption;

/**
 * A {@link ClientFactory} option.
 *
 * @param <T> the type of the option value
 */
public final class ClientFactoryOption<T>
        extends AbstractOption<ClientFactoryOption<T>, ClientFactoryOptionValue<T>, T> {

    /**
     * Returns the all available {@link ClientFactoryOption}s.
     */
    public static Set<ClientFactoryOption<?>> allOptions() {
        return allOptions(ClientFactoryOption.class);
    }

    /**
     * Returns the {@link ClientFactoryOption} with the specified {@code name}.
     *
     * @throws NoSuchElementException if there's no such option defined.
     */
    public static ClientFactoryOption<?> of(String name) {
        return of(ClientFactoryOption.class, name);
    }

    /**
     * Defines a new {@link ClientFactoryOption} of the specified name and default value.
     *
     * @param name the name of the option.
     * @param defaultValue the default value of the option, which will be used when unspecified.
     *
     * @throws IllegalStateException if an option with the specified name exists already.
     */
    public static <T> ClientFactoryOption<T> define(String name, T defaultValue) {
        return define(name, defaultValue, Function.identity(), (oldValue, newValue) -> newValue);
    }

    /**
     * Defines a new {@link ClientFactoryOption} of the specified name, default value and merge function.
     *
     * @param name the name of the option.
     * @param defaultValue the default value of the option, which will be used when unspecified.
     * @param validator the {@link Function} which is used for validating and normalizing an option value.
     * @param mergeFunction the {@link BiFunction} which is used for merging old and new option values.
     *
     * @throws IllegalStateException if an option with the specified name exists already.
     */
    public static <T> ClientFactoryOption<T> define(
            String name,
            T defaultValue,
            Function<T, T> validator,
            BiFunction<ClientFactoryOptionValue<T>,
                    ClientFactoryOptionValue<T>,
                    ClientFactoryOptionValue<T>> mergeFunction) {
        return define(ClientFactoryOption.class, name, defaultValue,
                      ClientFactoryOption::new, validator, mergeFunction);
    }

    private ClientFactoryOption(
            String name,
            T defaultValue,
            Function<T, T> validator,
            BiFunction<ClientFactoryOptionValue<T>,
                    ClientFactoryOptionValue<T>,
                    ClientFactoryOptionValue<T>> mergeFunction) {
        super(name, defaultValue, validator, mergeFunction);
    }

    @Override
    protected ClientFactoryOptionValue<T> doNewValue(T value) {
        return new ClientFactoryOptionValue<>(this, value);
    }
}

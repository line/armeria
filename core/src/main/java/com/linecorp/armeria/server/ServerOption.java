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
package com.linecorp.armeria.server;

import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.AbstractOption;

/**
 * A server option.
 *
 * @param <T> the type of the option value
 */
@UnstableApi
public final class ServerOption<T> extends AbstractOption<ServerOption<T>, ServerOptionValue<T>, T> {

    static <T> ServerOption<T> define(String name, T defaultValue) {
        return define(name, defaultValue, Function.identity());
    }

    static <T> ServerOption<T> define(String name, T defaultValue, Function<T, T> validator) {
        return define(ServerOption.class, name, defaultValue, ServerOption::new, validator,
                      (oldValue, newValue) -> newValue);
    }

    private ServerOption(
            String name,
            T defaultValue,
            Function<T, T> validator,
            BiFunction<ServerOptionValue<T>, ServerOptionValue<T>, ServerOptionValue<T>> mergeFunction) {
        super(name, defaultValue, validator, mergeFunction);
    }

    @Override
    protected ServerOptionValue<T> doNewValue(T value) {
        return new ServerOptionValue<>(this, value);
    }
}

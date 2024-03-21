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

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.common.util.SafeCloseable;

final class DefaultContextAwareFunction<T, R> implements ContextAwareFunction<T, R> {

    private final RequestContext context;
    private final Function<T, R> function;

    DefaultContextAwareFunction(RequestContext context, Function<T, R> function) {
        this.context = requireNonNull(context, "context");
        this.function = requireNonNull(function, "function");
    }

    @Override
    public RequestContext context() {
        return context;
    }

    @Override
    public Function<T, R> withoutContext() {
        return function;
    }

    @Override
    public R apply(T t) {
        try (SafeCloseable ignored = context.push()) {
            return function.apply(t);
        }
    }
}

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

import java.util.concurrent.Callable;
import java.util.function.Function;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

final class DefaultContextAwareCallable<T> implements ContextAwareCallable<T> {
    private final RequestContext context;
    private final Callable<T> callable;
    @Nullable
    private final Function<Throwable, T> exceptionHandler;

    DefaultContextAwareCallable(RequestContext context, Callable<T> callable) {
        this.context = requireNonNull(context, "context");
        this.callable = requireNonNull(callable, "callable");
        exceptionHandler = null;
    }

    DefaultContextAwareCallable(RequestContext context, Callable<T> callable,
                                Function<Throwable, T> exceptionHandler) {
        this.context = requireNonNull(context, "context");
        this.callable = requireNonNull(callable, "callable");
        this.exceptionHandler = requireNonNull(exceptionHandler, "exceptionHandler");
    }

    @Override
    public RequestContext context() {
        return context;
    }

    @Override
    public Callable<T> withoutContext() {
        return callable;
    }

    @Override
    public T call() throws Exception {
        try (SafeCloseable ignored = context.push()) {
            return callable.call();
        } catch (Throwable cause) {
            if (exceptionHandler == null) {
                throw cause;
            }
            return exceptionHandler.apply(cause);
        }
    }
}

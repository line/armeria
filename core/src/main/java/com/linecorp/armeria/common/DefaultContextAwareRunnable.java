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

import java.util.function.Consumer;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

final class DefaultContextAwareRunnable implements ContextAwareRunnable {
    private final RequestContext context;
    private final Runnable runnable;
    @Nullable
    private final Consumer<Throwable> exceptionHandler;

    DefaultContextAwareRunnable(RequestContext context, Runnable runnable) {
        this.context = requireNonNull(context, "context");
        this.runnable = requireNonNull(runnable, "runnable");
        exceptionHandler = null;
    }

    DefaultContextAwareRunnable(RequestContext context, Runnable runnable,
                                Consumer<Throwable> exceptionHandler) {
        this.context = requireNonNull(context, "context");
        this.runnable = requireNonNull(runnable, "runnable");
        this.exceptionHandler = requireNonNull(exceptionHandler, "exceptionHandler");
    }

    @Override
    public RequestContext context() {
        return context;
    }

    @Override
    public Runnable withoutContext() {
        return runnable;
    }

    @Override
    public void run() {
        try (SafeCloseable ignored = context.push()) {
            runnable.run();
        } catch (Throwable cause) {
            if (exceptionHandler == null) {
                throw cause;
            }
            exceptionHandler.accept(cause);
        }
    }
}

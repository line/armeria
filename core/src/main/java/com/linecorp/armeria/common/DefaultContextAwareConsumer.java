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

import com.linecorp.armeria.common.util.SafeCloseable;

final class DefaultContextAwareConsumer<T> implements ContextAwareConsumer<T> {
    private final RequestContext context;
    private final Consumer<T> action;

    DefaultContextAwareConsumer(RequestContext context, Consumer<T> action) {
        this.context = requireNonNull(context, "context");
        this.action = requireNonNull(action, "action");
    }

    @Override
    public RequestContext context() {
        return context;
    }

    @Override
    public Consumer<T> withoutContext() {
        return action;
    }

    @Override
    public void accept(T t) {
        try (SafeCloseable ignored = context.push()) {
            action.accept(t);
        }
    }
}

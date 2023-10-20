/*
 * Copyright 2020 LINE Corporation
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

import static com.linecorp.armeria.internal.common.RequestContextUtil.ensureSameCtx;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.Executor;

/**
 * A delegating {@link Executor} that makes sure all submitted tasks are
 * executed within the {@link RequestContext}.
 */
public interface ContextAwareExecutor extends Executor, ContextHolder {

    /**
     * Returns a new {@link ContextAwareExecutor} that sets the specified
     * {@link RequestContext} before executing any submitted tasks.
     */
    static ContextAwareExecutor of(RequestContext context, Executor executor) {
        requireNonNull(context, "context");
        requireNonNull(executor, "executor");
        if (executor instanceof ContextAwareExecutor) {
            ensureSameCtx(context, (ContextAwareExecutor) executor, ContextAwareExecutor.class);
            return (ContextAwareExecutor) executor;
        }
        return new DefaultContextAwareExecutor(context, executor);
    }

    /**
     * Returns the {@link RequestContext} that was specified when creating
     * this {@link ContextAwareExecutor}.
     */
    @Override
    RequestContext context();

    /**
     * Returns the {@link Executor} that executes the submitted tasks without setting
     * the {@link RequestContext}.
     */
    Executor withoutContext();
}

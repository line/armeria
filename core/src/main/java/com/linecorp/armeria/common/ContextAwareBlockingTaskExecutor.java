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

import static com.linecorp.armeria.internal.common.RequestContextUtil.ensureSameCtx;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.util.BlockingTaskExecutor;

/**
 * A delegating {@link BlockingTaskExecutor} that sets the {@link RequestContext} before executing
 * any submitted tasks.
 */
public interface ContextAwareBlockingTaskExecutor
        extends BlockingTaskExecutor, ContextAwareScheduledExecutorService {

    /**
     * Returns a new {@link ContextAwareBlockingTaskExecutor} that sets the specified {@link RequestContext}
     * before executing any submitted tasks.
     */
    static ContextAwareBlockingTaskExecutor of(RequestContext context, BlockingTaskExecutor executor) {
        requireNonNull(context, "context");
        requireNonNull(executor, "executor");
        if (executor instanceof ContextAwareBlockingTaskExecutor) {
            ensureSameCtx(context, (ContextAwareBlockingTaskExecutor) executor,
                          ContextAwareBlockingTaskExecutor.class);
            return (ContextAwareBlockingTaskExecutor) executor;
        }
        return new DefaultContextAwareBlockingTaskExecutor(context, executor);
    }

    /**
     * Returns the {@link RequestContext} that was specified when creating
     * this {@link ContextAwareBlockingTaskExecutor}.
     */
    @Override
    RequestContext context();

    /**
     * Returns the {@link BlockingTaskExecutor} that executes the submitted tasks without setting the
     * {@link RequestContext}.
     */
    @Override
    BlockingTaskExecutor withoutContext();
}

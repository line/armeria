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

import java.util.concurrent.ScheduledExecutorService;

/**
 * A delegating {@link ScheduledExecutorService} that sets the {@link RequestContext} before executing any
 * submitted tasks.
 */
public interface ContextAwareScheduledExecutorService
        extends ScheduledExecutorService, ContextAwareExecutorService {

    /**
     * Returns a new {@link ContextAwareScheduledExecutorService} that sets the specified
     * {@link RequestContext} before executing any submitted tasks.
     */
    static ContextAwareScheduledExecutorService of(
            RequestContext context, ScheduledExecutorService executor) {
        requireNonNull(context, "context");
        requireNonNull(executor, "executor");
        if (executor instanceof ContextAwareScheduledExecutorService) {
            ensureSameCtx(context, (ContextAwareScheduledExecutorService) executor,
                          ContextAwareScheduledExecutorService.class);
            return (ContextAwareScheduledExecutorService) executor;
        }
        return new DefaultContextAwareScheduledExecutorService(context, executor);
    }

    /**
     * Returns the {@link RequestContext} that was specified when creating
     * this {@link ContextAwareScheduledExecutorService}.
     */
    @Override
    RequestContext context();

    /**
     * Returns the {@link ScheduledExecutorService} that executes the submitted tasks without setting the
     * {@link RequestContext}.
     */
    @Override
    ScheduledExecutorService withoutContext();
}

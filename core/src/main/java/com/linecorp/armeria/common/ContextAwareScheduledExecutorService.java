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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ScheduledExecutorService;

/**
 * A delegating {@link ScheduledExecutorService} that sets the {@link RequestContext} before executing any
 * submitted tasks.
 */
public interface ContextAwareScheduledExecutorService extends ScheduledExecutorService {

    /**
     * Returns a new {@link ContextAwareScheduledExecutorService} that sets the specified
     * {@link RequestContext} before executing any submitted tasks.
     */
    static ContextAwareScheduledExecutorService of(
            RequestContext context, ScheduledExecutorService executor) {
        requireNonNull(context, "context");
        requireNonNull(executor, "executor");
        if (executor instanceof ContextAwareScheduledExecutorService) {
            final RequestContext ctx = ((ContextAwareScheduledExecutorService) executor).context();
            if (context == ctx) {
                return (ContextAwareScheduledExecutorService) executor;
            }
            throw new IllegalArgumentException(
                    "cannot create a " + ContextAwareScheduledExecutorService.class.getSimpleName() +
                    " using another " + executor);
        }
        return new DefaultContextAwareScheduledExecutorService(context, executor);
    }

    /**
     * Returns the {@link ScheduledExecutorService} that is executing submitted tasks without setting
     * the {@link RequestContext}.
     */
    ScheduledExecutorService withoutContext();

    /**
     * Returns the {@link RequestContext} that is specified when creating
     * this {@link ContextAwareScheduledExecutorService}.
     */
    RequestContext context();
}

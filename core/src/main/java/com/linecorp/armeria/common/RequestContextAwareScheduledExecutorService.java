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

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.google.common.base.MoreObjects;

/**
 * A delegating {@link ScheduledExecutorService} that sets the {@link RequestContext} before executing any
 * submitted tasks.
 */
public final class RequestContextAwareScheduledExecutorService
        extends RequestContextAwareExecutorService implements ScheduledExecutorService {

    /**
     * Returns a new {@link RequestContextAwareScheduledExecutorService} that sets the specified
     * {@link RequestContext} before executing any submitted tasks.
     */
    public static RequestContextAwareScheduledExecutorService of(
            RequestContext context, ScheduledExecutorService executor) {
        requireNonNull(context, "context");
        requireNonNull(executor, "executor");
        if (executor instanceof RequestContextAwareScheduledExecutorService) {
            final RequestContext ctx = ((RequestContextAwareScheduledExecutorService) executor).context();
            if (context == ctx) {
                return (RequestContextAwareScheduledExecutorService) executor;
            }
            throw new IllegalArgumentException(
                    "cannot create a " + RequestContextAwareScheduledExecutorService.class.getSimpleName() +
                    " using another " + executor);
        }
        return new RequestContextAwareScheduledExecutorService(context, executor);
    }

    private final ScheduledExecutorService executor;

    private RequestContextAwareScheduledExecutorService(
            RequestContext context, ScheduledExecutorService executor) {
        super(context, executor);
        this.executor = executor;
    }

    /**
     * Returns the {@link ScheduledExecutorService} that is executing submitted tasks without setting
     * the {@link RequestContext}.
     */
    public ScheduledExecutorService detachContext() {
        return executor;
    }

    /**
     * Returns the {@link RequestContext} that is specified when creating
     * this {@link RequestContextAwareScheduledExecutorService}.
     */
    @Override
    public RequestContext context() {
        return super.context();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return executor.schedule(context().makeContextAware(command), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return executor.schedule(context().makeContextAware(callable), delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                  TimeUnit unit) {
        return executor.scheduleAtFixedRate(context().makeContextAware(command), initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                     TimeUnit unit) {
        return executor.scheduleWithFixedDelay(context().makeContextAware(command),
                                                 initialDelay, delay, unit);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("executor", executor)
                          .add("context", context())
                          .toString();
    }
}

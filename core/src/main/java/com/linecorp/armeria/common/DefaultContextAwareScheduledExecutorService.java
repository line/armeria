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

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.google.common.base.MoreObjects;

final class DefaultContextAwareScheduledExecutorService
        extends ContextAwareExecutorService implements ContextAwareScheduledExecutorService {

    private final ScheduledExecutorService executor;

    DefaultContextAwareScheduledExecutorService(
            RequestContext context, ScheduledExecutorService executor) {
        super(context, executor);
        this.executor = executor;
    }

    /**
     * Returns the {@link ScheduledExecutorService} that is executing submitted tasks without setting
     * the {@link RequestContext}.
     */
    @Override
    public ScheduledExecutorService withoutContext() {
        return executor;
    }

    /**
     * Returns the {@link RequestContext} that is specified when creating
     * this {@link ContextAwareScheduledExecutorService}.
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

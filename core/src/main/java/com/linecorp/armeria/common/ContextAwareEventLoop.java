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

import static com.linecorp.armeria.common.RequestContextUtil.ensureSameCtx;
import static java.util.Objects.requireNonNull;

import io.netty.channel.EventLoop;

/**
 * A delegating {@link EventLoop} that sets the {@link RequestContext} before executing any submitted tasks.
 */
public interface ContextAwareEventLoop extends EventLoop, ContextAwareScheduledExecutorService {

    /**
     * Returns a new {@link ContextAwareEventLoop} that sets the specified {@link RequestContext}
     * before executing any submitted tasks.
     */
    static ContextAwareEventLoop of(RequestContext context, EventLoop eventLoop) {
        requireNonNull(context, "context");
        requireNonNull(eventLoop, "eventLoop");
        if (eventLoop instanceof ContextAwareEventLoop) {
            ensureSameCtx(context, (ContextAwareEventLoop) eventLoop, ContextAwareEventLoop.class);
            return (ContextAwareEventLoop) eventLoop;
        }
        return new DefaultContextAwareEventLoop(context, eventLoop);
    }

    /**
     * Returns the {@link EventLoop} that is executing submitted tasks without setting
     * the {@link RequestContext}.
     */
    @Override
    EventLoop withoutContext();

    /**
     * Returns the {@link RequestContext} that is specified when creating
     * this {@link ContextAwareEventLoop}.
     */
    @Override
    RequestContext context();

    /**
     * Return {@code true} if the {@link Thread#currentThread()} is executed in this event loop and
     * the thread has the same {@link #context()}, {@code false} otherwise.
     */
    boolean inContextAwareEventLoop();
}

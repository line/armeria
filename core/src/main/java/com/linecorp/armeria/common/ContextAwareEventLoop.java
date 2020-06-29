/*
 * Copyright 2016 LINE Corporation
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

import io.netty.channel.EventLoop;

/**
 * A delegating {@link EventLoop} that sets the {@link RequestContext} before executing any submitted tasks.
 */
public interface ContextAwareEventLoop extends EventLoop {

    /**
     * Returns a new {@link ContextAwareEventLoop} that sets the specified {@link RequestContext}
     * before executing any submitted tasks.
     */
    static ContextAwareEventLoop of(RequestContext context, EventLoop eventLoop) {
        requireNonNull(context, "context");
        requireNonNull(eventLoop, "eventLoop");
        if (eventLoop instanceof ContextAwareEventLoop) {
            final RequestContext ctx = ((ContextAwareEventLoop) eventLoop).context();
            if (context == ctx) {
                return (ContextAwareEventLoop) eventLoop;
            }
            throw new IllegalArgumentException(
                    "cannot create a " + ContextAwareEventLoop.class.getSimpleName() +
                    " using another " + eventLoop);
        }
        return new DefaultContextAwareEventLoop(context, eventLoop);
    }

    /**
     * Returns the {@link EventLoop} that is executing submitted tasks without setting
     * the {@link RequestContext}.
     */
    EventLoop withoutContext();

    /**
     * Returns the {@link RequestContext} that is specified when creating
     * this {@link ContextAwareEventLoop}.
     */
    RequestContext context();
}

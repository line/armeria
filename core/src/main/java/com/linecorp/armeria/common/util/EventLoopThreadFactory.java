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

package com.linecorp.armeria.common.util;

import java.util.concurrent.ThreadFactory;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * {@link ThreadFactory} that creates event loop threads.
 *
 * @see EventLoopGroups
 *
 * @deprecated Use {@link ThreadFactories#newEventLoopThreadFactory(String, boolean)} or
 *             {@link ThreadFactories#builder(String)}. Note that setting
 *             {@link ThreadFactoryBuilder#eventLoop(boolean)} true is required to create event loop threads,
 *             e.g. {@code ThreadFactories.builder("myThreadNamePrefix").eventLoop(true).build()}.
 */
@Deprecated
public final class EventLoopThreadFactory extends AbstractThreadFactory {
    /**
     * Creates a new factory that creates a non-daemon and normal-priority thread.
     *
     * @param threadNamePrefix the prefix of the names of the threads created by this factory.
     */
    public EventLoopThreadFactory(String threadNamePrefix) {
        super(threadNamePrefix, false, Thread.NORM_PRIORITY, null, Function.identity());
    }

    /**
     * Creates a new factory that creates a normal-priority thread.
     *
     * @param threadNamePrefix the prefix of the names of the threads created by this factory.
     * @param daemon whether to create a daemon thread.
     */
    public EventLoopThreadFactory(String threadNamePrefix, boolean daemon) {
        super(threadNamePrefix, daemon, Thread.NORM_PRIORITY, null, Function.identity());
    }

    /**
     * Creates a new factory that creates a non-daemon thread.
     *
     * @param threadNamePrefix the prefix of the names of the threads created by this factory.
     * @param priority the priority of the threads created by this factory.
     */
    public EventLoopThreadFactory(String threadNamePrefix, int priority) {
        super(threadNamePrefix, false, priority, null, Function.identity());
    }

    /**
     * Creates a new factory.
     *
     * @param threadNamePrefix the prefix of the names of the threads created by this factory.
     * @param daemon whether to create a daemon thread.
     * @param priority the priority of the threads created by this factory.
     */
    public EventLoopThreadFactory(String threadNamePrefix, boolean daemon, int priority) {
        super(threadNamePrefix, daemon, priority, null, Function.identity());
    }

    /**
     * Creates a new factory.
     *
     * @param threadNamePrefix the prefix of the names of the threads created by this factory.
     * @param daemon whether to create a daemon thread.
     * @param priority the priority of the threads created by this factory.
     * @param threadGroup the {@link ThreadGroup}.
     */
    public EventLoopThreadFactory(String threadNamePrefix, boolean daemon, int priority,
                                  @Nullable ThreadGroup threadGroup) {
        super(threadNamePrefix, daemon, priority, threadGroup, Function.identity());
    }

    EventLoopThreadFactory(String threadNamePrefix, boolean daemon, int priority,
                           @Nullable ThreadGroup threadGroup,
                           Function<? super Runnable, ? extends Runnable> taskFunction) {
        super(threadNamePrefix, daemon, priority, threadGroup, taskFunction);
    }

    @Override
    Thread newThread(@Nullable ThreadGroup threadGroup, Runnable r, String name) {
        return new EventLoopThread(threadGroup, r, name);
    }
}

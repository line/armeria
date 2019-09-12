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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ThreadFactory;
import java.util.function.Function;

import javax.annotation.Nullable;

import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * {@link ThreadFactory} that creates event loop threads.
 *
 * @see EventLoopGroups
 */
public final class EventLoopThreadFactory implements ThreadFactory {
    /**
     * Returns a new builder which builds a new {@link EventLoopThreadFactory}.
     */
    public static EventLoopThreadFactoryBuilder builder(String threadNamePrefix) {
        return new EventLoopThreadFactoryBuilder(requireNonNull(threadNamePrefix, "threadNamePrefix"));
    }

    // Note that we did not extend DefaultThreadFactory directly to hide it from the class hierarchy.
    private final ThreadFactory delegate;

    @Nullable
    private Function<? super Runnable, ? extends Runnable> taskFunction;

    /**
     * Creates a new factory that creates a non-daemon and normal-priority thread.
     *
     * @param threadNamePrefix the prefix of the names of the threads created by this factory.
     *
     * @deprecated Use {@link #builder(String)}.
     */
    @Deprecated
    public EventLoopThreadFactory(String threadNamePrefix) {
        this(new EventLoopThreadFactoryImpl(requireNonNull(threadNamePrefix, "threadNamePrefix")));
    }

    /**
     * Creates a new factory that creates a normal-priority thread.
     *
     * @param threadNamePrefix the prefix of the names of the threads created by this factory.
     * @param daemon whether to create a daemon thread.
     *
     * @deprecated Use {@link #builder(String)}.
     */
    @Deprecated
    public EventLoopThreadFactory(String threadNamePrefix, boolean daemon) {
        this(new EventLoopThreadFactoryImpl(requireNonNull(threadNamePrefix, "threadNamePrefix"), daemon));
    }

    /**
     * Creates a new factory that creates a non-daemon thread.
     *
     * @param threadNamePrefix the prefix of the names of the threads created by this factory.
     * @param priority the priority of the threads created by this factory.
     *
     * @deprecated Use {@link #builder(String)}.
     */
    @Deprecated
    public EventLoopThreadFactory(String threadNamePrefix, int priority) {
        this(new EventLoopThreadFactoryImpl(requireNonNull(threadNamePrefix, "threadNamePrefix"), priority));
    }

    /**
     * Creates a new factory.
     *
     * @param threadNamePrefix the prefix of the names of the threads created by this factory.
     * @param daemon whether to create a daemon thread.
     * @param priority the priority of the threads created by this factory.
     *
     * @deprecated Use {@link #builder(String)}.
     */
    @Deprecated
    public EventLoopThreadFactory(String threadNamePrefix, boolean daemon, int priority) {
        this(new EventLoopThreadFactoryImpl(requireNonNull(threadNamePrefix, "threadNamePrefix"),
                                            daemon, priority));
    }

    /**
     * Creates a new factory.
     *
     * @param threadNamePrefix the prefix of the names of the threads created by this factory.
     * @param daemon whether to create a daemon thread.
     * @param priority the priority of the threads created by this factory.
     * @param threadGroup the {@link ThreadGroup}.
     *
     * @deprecated Use {@link #builder(String)}.
     */
    @Deprecated
    public EventLoopThreadFactory(String threadNamePrefix, boolean daemon, int priority,
                                  @Nullable ThreadGroup threadGroup) {
        this(new EventLoopThreadFactoryImpl(requireNonNull(threadNamePrefix, "threadNamePrefix"),
                                            daemon, priority, threadGroup));
    }

    private EventLoopThreadFactory(ThreadFactory delegate) {
        this.delegate = delegate;
    }

    EventLoopThreadFactory(String threadNamePrefix, boolean daemon, int priority,
                           @Nullable ThreadGroup threadGroup,
                           Function<? super Runnable, ? extends Runnable> taskFunction) {
        this(new EventLoopThreadFactoryImpl(requireNonNull(threadNamePrefix, "threadNamePrefix"),
                                            daemon, priority, threadGroup));
        this.taskFunction = requireNonNull(taskFunction, "taskFunction");
    }

    @Override
    public Thread newThread(Runnable r) {
        return delegate.newThread(taskFunction.apply(r));
    }

    static final class EventLoopThreadFactoryImpl extends DefaultThreadFactory {
        EventLoopThreadFactoryImpl(String threadNamePrefix) {
            super(threadNamePrefix);
        }

        EventLoopThreadFactoryImpl(String threadNamePrefix, boolean daemon) {
            super(threadNamePrefix, daemon);
        }

        EventLoopThreadFactoryImpl(String threadNamePrefix, int priority) {
            super(threadNamePrefix, priority);
        }

        EventLoopThreadFactoryImpl(String threadNamePrefix, boolean daemon, int priority) {
            super(threadNamePrefix, daemon, priority);
        }

        EventLoopThreadFactoryImpl(String threadNamePrefix, boolean daemon, int priority,
                                   @Nullable ThreadGroup threadGroup) {
            super(threadNamePrefix, daemon, priority, threadGroup);
        }

        @Override
        protected Thread newThread(Runnable r, String name) {
            return new EventLoopThread(threadGroup, r, name);
        }
    }
}

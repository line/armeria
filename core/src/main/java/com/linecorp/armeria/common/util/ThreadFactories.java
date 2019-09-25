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

/**
 * Provides a builder for {@link ThreadFactory}.
 */
public final class ThreadFactories {
    /**
     * Returns a new builder which builds a new {@link ThreadFactory}.
     *
     * @param threadNamePrefix the prefix of the names of the threads created by the factory
     *                         built by this factory builder.
     */
    public static ThreadFactoryBuilder builder(String threadNamePrefix) {
        return new ThreadFactoryBuilder(requireNonNull(threadNamePrefix, "threadNamePrefix"));
    }

    /**
     * Creates a new {@link ThreadFactory} for event loop thread.
     * This is a shortcut method of
     * {@code ThreadFactories.builder("threadNamePrefix").eventLoop(true).daemon(daemon).build()}.
     *
     * @param threadNamePrefix the prefix of the names of the threads created by this factory.
     * @param daemon whether to create a daemon thread.
     */
    public static ThreadFactory newEventLoopThreadFactory(String threadNamePrefix, boolean daemon) {

        return builder(threadNamePrefix).eventLoop(true).daemon(daemon).build();
    }

    /**
     * Creates a new {@link ThreadFactory} for non event loop thread.
     * This is a shortcut method of {@code ThreadFactories.builder("threadNamePrefix").daemon(daemon).build()}.
     *
     * @param threadNamePrefix the prefix of the names of the threads created by this factory.
     * @param daemon whether to create a daemon thread.
     */
    public static ThreadFactory newThreadFactory(String threadNamePrefix, boolean daemon) {
        return builder(threadNamePrefix).daemon(daemon).build();
    }

    private ThreadFactories() {}
}

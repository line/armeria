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
     */
    public static ThreadFactoryBuilder builder(String threadPrefix) {
        return new ThreadFactoryBuilder(requireNonNull(threadPrefix, "threadPrefix"));
    }

    /**
     * Creates a new {@link ThreadFactory} for event loop thread.
     * This is a shortcut method of
     * {@code ThreadFactories.builder("threadPrefix").eventLoop(true).daemon(daemon).build()}.
     */
    public static ThreadFactory newEventLoopThreadFactory(String threadPrefix, boolean daemon) {

        return builder(requireNonNull(threadPrefix, "threadPrefix")).eventLoop(true)
                                                                    .daemon(daemon)
                                                                    .build();
    }

    /**
     * Creates a new {@link ThreadFactory} for non event loop thread.
     * This is a shortcut method of {@code ThreadFactories.builder("threadPrefix").daemon(daemon).build()}.
     */
    public static ThreadFactory newThreadFactory(String threadPrefix, boolean daemon) {
        return builder(requireNonNull(threadPrefix, "threadPrefix")).daemon(daemon).build();
    }

    private ThreadFactories() {}
}

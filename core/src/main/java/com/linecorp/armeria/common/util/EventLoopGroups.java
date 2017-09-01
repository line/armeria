/*
 * Copyright 2017 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.ThreadFactory;

import com.linecorp.armeria.internal.TransportType;

import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * Provides methods that are useful for creating an {@link EventLoopGroup}.
 */
public final class EventLoopGroups {

    /**
     * Returns a newly-created {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     */
    public static EventLoopGroup newEventLoopGroup(int numThreads) {
        return newEventLoopGroup(numThreads, false);
    }

    /**
     * Returns a newly-created {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     * @param useDaemonThreads whether to create daemon threads or not
     */
    public static EventLoopGroup newEventLoopGroup(int numThreads, boolean useDaemonThreads) {
        return newEventLoopGroup(numThreads, "armeria-eventloop", useDaemonThreads);
    }

    /**
     * Returns a newly-created {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     * @param threadNamePrefix the prefix of thread names
     */
    public static EventLoopGroup newEventLoopGroup(int numThreads, String threadNamePrefix) {
        return newEventLoopGroup(numThreads, threadNamePrefix, false);
    }

    /**
     * Returns a newly-created {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     * @param threadNamePrefix the prefix of thread names
     * @param useDaemonThreads whether to create daemon threads or not
     */
    public static EventLoopGroup newEventLoopGroup(int numThreads, String threadNamePrefix,
                                                   boolean useDaemonThreads) {

        checkArgument(numThreads > 0, "numThreads: %s (expected: > 0)", numThreads);
        requireNonNull(threadNamePrefix, "threadNamePrefix");

        final TransportType type = TransportType.detectTransportType();
        final String prefix = threadNamePrefix + '-' + type.lowerCasedName();
        return newEventLoopGroup(numThreads, new DefaultThreadFactory(prefix, useDaemonThreads));
    }

    /**
     * Returns a newly-created {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     * @param threadFactory the factory of event loop threads
     */
    public static EventLoopGroup newEventLoopGroup(int numThreads, ThreadFactory threadFactory) {

        checkArgument(numThreads > 0, "numThreads: %s (expected: > 0)", numThreads);
        requireNonNull(threadFactory, "threadFactory");

        final TransportType type = TransportType.detectTransportType();
        return type.newEventLoopGroup(numThreads, unused -> threadFactory);
    }

    private EventLoopGroups() {}
}

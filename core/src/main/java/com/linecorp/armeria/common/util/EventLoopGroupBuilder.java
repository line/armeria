/*
 * Copyright 2026 LINE Corporation
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

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import io.netty.channel.EventLoopGroup;

import java.time.Duration;
import java.util.concurrent.ThreadFactory;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A builder for creating an {@link EventLoopGroup} with custom configuration.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * EventLoopGroup eventLoopGroup = EventLoopGroups
 *     .builder()
 *     .numThreads(4)
 *     .threadFactory(ThreadFactories.newThreadFactory("my-eventloop", true))
 *     .gracefulShutdown(Duration.ofSeconds(2), Duration.ofSeconds(15))
 *     .build();
 * }</pre>
 *
 * @see EventLoopGroups#builder()
 */
@UnstableApi
public final class EventLoopGroupBuilder {

    public static final String DEFAULT_ARMERIA_THREAD_NAME_PREFIX = "armeria-eventloop";

    // Netty defaults from AbstractEventExecutorGroup.shutdownGracefully()
    private static final long DEFAULT_SHUTDOWN_QUIET_PERIOD_MILLIS = 2000;
    private static final long DEFAULT_SHUTDOWN_TIMEOUT_MILLIS = 15000;

    private int numThreads = Flags.numCommonWorkers();
    @Nullable
    private ThreadFactory threadFactory;
    private long shutdownQuietPeriodMillis = DEFAULT_SHUTDOWN_QUIET_PERIOD_MILLIS;
    private long shutdownTimeoutMillis = DEFAULT_SHUTDOWN_TIMEOUT_MILLIS;

    EventLoopGroupBuilder() {}

    /**
     * Sets the number of event loop threads. The default is {@link Flags#numCommonWorkers()},
     * which is typically the number of available processors multiplied by 2.
     *
     * @param numThreads the number of threads (must be greater than 0)
     */
    public EventLoopGroupBuilder numThreads(int numThreads) {
        checkArgument(numThreads > 0, "numThreads: %s (expected: > 0)", numThreads);
        this.numThreads = numThreads;
        return this;
    }

    /**
     * Sets a custom {@link ThreadFactory} for creating event loop threads.
     *
     * @param threadFactory the thread factory to use
     * @see ThreadFactories for convenient factory methods to create a {@link ThreadFactory}
     */
    public EventLoopGroupBuilder threadFactory(ThreadFactory threadFactory) {
        this.threadFactory = requireNonNull(threadFactory, "threadFactory");
        return this;
    }

    /**
     * Sets the graceful shutdown quiet period and timeout.
     * The quiet period is the amount of time the executor will wait for new tasks before
     * starting to shut down. The timeout is the maximum amount of time to wait for the
     * executor to terminate.
     *
     * <p>The default values are 2 seconds for quiet period and 15 seconds for timeout,
     * which are Netty's default values.
     *
     * @param quietPeriod the quiet period during which the executor will wait for new tasks
     * @param timeout the maximum time to wait for the executor to terminate
     */
    public EventLoopGroupBuilder gracefulShutdown(Duration quietPeriod, Duration timeout) {
        requireNonNull(quietPeriod, "quietPeriod");
        requireNonNull(timeout, "timeout");
        return gracefulShutdownMillis(quietPeriod.toMillis(), timeout.toMillis());
    }

    /**
     * Sets the graceful shutdown quiet period and timeout in milliseconds.
     * The quiet period is the amount of time the executor will wait for new tasks before
     * starting to shut down. The timeout is the maximum amount of time to wait for the
     * executor to terminate.
     *
     * <p>The default values are 2000ms for quiet period and 15000ms for timeout,
     * which are Netty's default values.
     *
     * @param quietPeriodMillis the quiet period in milliseconds (must be &gt;= 0)
     * @param timeoutMillis the timeout in milliseconds (must be &gt;= quietPeriodMillis)
     */
    public EventLoopGroupBuilder gracefulShutdownMillis(long quietPeriodMillis, long timeoutMillis) {
        checkArgument(quietPeriodMillis >= 0,
                      "quietPeriodMillis: %s (expected: >= 0)", quietPeriodMillis);
        checkArgument(timeoutMillis >= quietPeriodMillis,
                      "timeoutMillis: %s (expected: >= quietPeriodMillis)", timeoutMillis);
        shutdownQuietPeriodMillis = quietPeriodMillis;
        shutdownTimeoutMillis = timeoutMillis;
        return this;
    }

    /**
     * Returns a newly-created {@link EventLoopGroup} with the properties set on this builder.
     * If graceful shutdown parameters have been configured, the returned {@link EventLoopGroup}
     * will use those parameters when {@link EventLoopGroup#shutdownGracefully()} is called.
     */
    public EventLoopGroup build() {
        final TransportType type = Flags.transportType();
        final ThreadFactory factory;
        if (threadFactory != null) {
            factory = threadFactory;
        } else {
            final String prefix = DEFAULT_ARMERIA_THREAD_NAME_PREFIX + '-' + type.lowerCasedName();
            factory = ThreadFactories.newEventLoopThreadFactory(prefix, false);
        }

        final EventLoopGroup eventLoopGroup = type.newEventLoopGroup(numThreads, unused -> factory);

        // Wrap with shutdown configuration if non-default values are used
        if (shutdownQuietPeriodMillis != DEFAULT_SHUTDOWN_QUIET_PERIOD_MILLIS ||
            shutdownTimeoutMillis != DEFAULT_SHUTDOWN_TIMEOUT_MILLIS) {
            return new ShutdownConfigurableEventLoopGroup(
                    eventLoopGroup, shutdownQuietPeriodMillis, shutdownTimeoutMillis);
        }
        return eventLoopGroup;
    }
}

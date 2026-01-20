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

import java.util.concurrent.TimeUnit;

import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;

/**
 * An {@link EventLoopGroup} wrapper that applies pre-configured graceful shutdown parameters
 * when {@link #shutdownGracefully()} is called.
 */
final class ShutdownConfigurableEventLoopGroup extends DelegatingEventLoopGroup {

    private final long shutdownQuietPeriodMillis;
    private final long shutdownTimeoutMillis;

    /**
     * Creates a new instance.
     *
     * @param delegate the {@link EventLoopGroup} to delegate to
     * @param shutdownQuietPeriodMillis the quiet period in milliseconds for graceful shutdown
     * @param shutdownTimeoutMillis the timeout in milliseconds for graceful shutdown
     */
    ShutdownConfigurableEventLoopGroup(EventLoopGroup delegate,
                                       long shutdownQuietPeriodMillis,
                                       long shutdownTimeoutMillis) {
        super(delegate);
        this.shutdownQuietPeriodMillis = shutdownQuietPeriodMillis;
        this.shutdownTimeoutMillis = shutdownTimeoutMillis;
    }

    /**
     * Signals this executor that the caller wants the executor to be shut down gracefully
     * using the pre-configured quiet period and timeout.
     */
    @Override
    public Future<?> shutdownGracefully() {
        return delegate().shutdownGracefully(
                shutdownQuietPeriodMillis, shutdownTimeoutMillis, TimeUnit.MILLISECONDS);
    }
}

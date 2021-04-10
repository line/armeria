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

package com.linecorp.armeria.common;

import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.server.ServerBuilder;

import io.netty.channel.EventLoopGroup;

/**
 * Provides the common shared thread pools and {@link EventLoopGroup}s which is used when not overridden.
 */
public final class CommonPools {

    private static final BlockingTaskExecutor BLOCKING_TASK_EXECUTOR;
    private static final EventLoopGroup WORKER_GROUP;

    static {
        // Threads spawned as needed and reused, with a 60s timeout and unbounded work queue.
        BLOCKING_TASK_EXECUTOR = BlockingTaskExecutor.of();

        WORKER_GROUP = EventLoopGroups.newEventLoopGroup(Flags.numCommonWorkers(),
                                                         "armeria-common-worker", true);
    }

    /**
     * Returns the default common blocking task {@link BlockingTaskExecutor} which is used for
     * potentially long-running tasks which may block I/O threads.
     */
    public static BlockingTaskExecutor blockingTaskExecutor() {
        return BLOCKING_TASK_EXECUTOR;
    }

    /**
     * Returns the common worker {@link EventLoopGroup} which is used when
     * {@link ServerBuilder#workerGroup(EventLoopGroup, boolean)} or
     * {@link ClientFactoryBuilder#workerGroup(EventLoopGroup, boolean)} is not specified.
     */
    public static EventLoopGroup workerGroup() {
        return WORKER_GROUP;
    }

    private CommonPools() {}
}

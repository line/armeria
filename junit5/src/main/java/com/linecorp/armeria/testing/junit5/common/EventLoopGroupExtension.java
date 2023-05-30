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

package com.linecorp.armeria.testing.junit5.common;

import java.util.concurrent.ThreadFactory;

import org.junit.jupiter.api.extension.Extension;

import com.linecorp.armeria.common.util.ThreadFactories;

import io.netty.channel.EventLoopGroup;

/**
 * An {@link Extension} that provides an {@link EventLoopGroup}. For example:
 *
 * <pre>{@code
 * > public class MyTest {
 * >     @RegisterExtension
 * >     public static final EventLoopGroupExtension eventLoopGroup = new EventLoopGroupExtension(4);
 * >
 * >     @Test
 * >     public void test() {
 * >         ClientFactory f = ClientFactory.builder()
 * >                                        .workerGroup(eventLoopGroup.get())
 * >                                        .build();
 * >         ...
 * >     }
 * > }
 * }</pre>
 *
 * @see EventLoopExtension
 */
public class EventLoopGroupExtension extends AbstractEventLoopGroupExtension {

    /**
     * Creates a new {@link Extension} that provides an {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     */
    public EventLoopGroupExtension(int numThreads) {
        this(numThreads, false);
    }

    /**
     * Creates a new {@link Extension} that provides an {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     * @param useDaemonThreads whether to create daemon threads or not
     */
    public EventLoopGroupExtension(int numThreads, boolean useDaemonThreads) {
        this(numThreads, "armeria-testing-eventloop", useDaemonThreads);
    }

    /**
     * Creates a new {@link Extension} that provides an {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     * @param threadNamePrefix the prefix of thread names
     */
    public EventLoopGroupExtension(int numThreads, String threadNamePrefix) {
        this(numThreads, threadNamePrefix, false);
    }

    /**
     * Creates a new {@link Extension} that provides an {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     * @param threadNamePrefix the prefix of thread names
     * @param useDaemonThreads whether to create daemon threads or not
     */
    public EventLoopGroupExtension(int numThreads, String threadNamePrefix, boolean useDaemonThreads) {
        this(numThreads, ThreadFactories.newEventLoopThreadFactory(threadNamePrefix, useDaemonThreads));
    }

    /**
     * Creates a new {@link Extension} that provides an {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     * @param threadFactory the factory used to create threads.
     */
    public EventLoopGroupExtension(int numThreads, ThreadFactory threadFactory) {
        super(numThreads, threadFactory);
    }

    /**
     * Returns the {@link EventLoopGroup}.
     */
    public EventLoopGroup get() {
        return group();
    }
}

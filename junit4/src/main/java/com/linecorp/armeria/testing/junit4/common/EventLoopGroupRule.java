/*
 *  Copyright 2018 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.testing.junit4.common;

import org.junit.rules.TestRule;

import io.netty.channel.EventLoopGroup;

/**
 * A {@link TestRule} that provides an {@link EventLoopGroup}. For example:
 *
 * <pre>{@code
 * > public class MyTest {
 * >     @ClassRule
 * >     public static final EventLoopGroupRule eventLoopGroup = new EventLoopGroupRule(4);
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
 * @see EventLoopRule
 */
public final class EventLoopGroupRule extends AbstractEventLoopGroupRule {

    /**
     * Creates a new {@link TestRule} that provides an {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     */
    public EventLoopGroupRule(int numThreads) {
        this(numThreads, false);
    }

    /**
     * Creates a new {@link TestRule} that provides an {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     * @param useDaemonThreads whether to create daemon threads or not
     */
    public EventLoopGroupRule(int numThreads, boolean useDaemonThreads) {
        this(numThreads, "armeria-testing-eventloop", useDaemonThreads);
    }

    /**
     * Creates a new {@link TestRule} that provides an {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     * @param threadNamePrefix the prefix of thread names
     */
    public EventLoopGroupRule(int numThreads, String threadNamePrefix) {
        this(numThreads, threadNamePrefix, false);
    }

    /**
     * Creates a new {@link TestRule} that provides an {@link EventLoopGroup}.
     *
     * @param numThreads the number of event loop threads
     * @param threadNamePrefix the prefix of thread names
     * @param useDaemonThreads whether to create daemon threads or not
     */
    public EventLoopGroupRule(int numThreads, String threadNamePrefix, boolean useDaemonThreads) {
        super(numThreads, threadNamePrefix, useDaemonThreads);
    }

    /**
     * Returns the {@link EventLoopGroup}.
     */
    public EventLoopGroup get() {
        return group();
    }
}

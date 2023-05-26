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

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

/**
 * An {@link Extension} that provides an {@link EventLoopGroup}. For example:
 *
 * <pre>{@code
 * > public class MyTest {
 * >     @RegisterExtension
 * >     public static final EventLoopExtension eventLoop = new EventLoopExtension();
 * >
 * >     @Test
 * >     public void test() {
 * >         eventLoop.get().execute(() -> System.out.println("Hello!"));
 * >     }
 * > }
 * }</pre>
 *
 * @see EventLoopGroupExtension
 */
public class EventLoopExtension extends AbstractEventLoopGroupExtension {

    /**
     * Creates a new {@link Extension} that provides an {@link EventLoop}.
     */
    public EventLoopExtension() {
        this(false);
    }

    /**
     * Creates a new {@link Extension} that provides an {@link EventLoop}.
     *
     * @param useDaemonThread whether to create a daemon thread or not
     */
    public EventLoopExtension(boolean useDaemonThread) {
        this("armeria-testing-eventloop", useDaemonThread);
    }

    /**
     * Creates a new {@link Extension} that provides an {@link EventLoop}.
     *
     * @param threadNamePrefix the prefix of a thread name
     */
    public EventLoopExtension(String threadNamePrefix) {
        this(threadNamePrefix, false);
    }

    /**
     * Creates a new {@link Extension} that provides an {@link EventLoop}.
     *
     * @param threadNamePrefix the prefix of a thread name
     * @param useDaemonThread whether to create a daemon thread or not
     */
    public EventLoopExtension(String threadNamePrefix, boolean useDaemonThread) {
        this(ThreadFactories.newEventLoopThreadFactory(threadNamePrefix, useDaemonThread));
    }

    /**
     * Creates a new {@link Extension} that provides an {@link EventLoop}.
     *
     * @param threadFactory the factory used to create threads.
     */
    public EventLoopExtension(ThreadFactory threadFactory) {
        super(1, threadFactory);
    }

    /**
     * Returns the {@link EventLoop}.
     */
    public EventLoop get() {
        return group().next();
    }
}

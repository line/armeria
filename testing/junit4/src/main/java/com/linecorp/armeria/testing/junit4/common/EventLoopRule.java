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

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

/**
 * A {@link TestRule} that provides an {@link EventLoopGroup}. For example:
 *
 * <pre>{@code
 * > public class MyTest {
 * >     @ClassRule
 * >     public static final EventLoopRule eventLoop = new EventLoopRule();
 * >
 * >     @Test
 * >     public void test() {
 * >         eventLoop.get().execute(() -> System.out.println("Hello!"));
 * >     }
 * > }
 * }</pre>
 *
 * @see EventLoopGroupRule
 */
public final class EventLoopRule extends AbstractEventLoopGroupRule {

    /**
     * Creates a new {@link TestRule} that provides an {@link EventLoop}.
     */
    public EventLoopRule() {
        this(false);
    }

    /**
     * Creates a new {@link TestRule} that provides an {@link EventLoop}.
     *
     * @param useDaemonThread whether to create a daemon thread or not
     */
    public EventLoopRule(boolean useDaemonThread) {
        this("armeria-testing-eventloop", useDaemonThread);
    }

    /**
     * Creates a new {@link TestRule} that provides an {@link EventLoop}.
     *
     * @param threadNamePrefix the prefix of a thread name
     */
    public EventLoopRule(String threadNamePrefix) {
        this(threadNamePrefix, false);
    }

    /**
     * Creates a new {@link TestRule} that provides an {@link EventLoop}.
     *
     * @param threadNamePrefix the prefix of a thread name
     * @param useDaemonThread whether to create a daemon thread or not
     */
    public EventLoopRule(String threadNamePrefix, boolean useDaemonThread) {
        super(1, threadNamePrefix, useDaemonThread);
    }

    /**
     * Returns the {@link EventLoop}.
     */
    public EventLoop get() {
        return group().next();
    }
}

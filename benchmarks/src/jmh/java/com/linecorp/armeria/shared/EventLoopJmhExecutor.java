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

package com.linecorp.armeria.shared;

import java.util.concurrent.Executor;

import com.linecorp.armeria.common.util.ThreadFactories;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoop;
import io.netty.channel.MultithreadEventLoopGroup;
import io.netty.util.concurrent.FastThreadLocal;

/**
 * A {@link MultithreadEventLoopGroup} that can be used as the JMH executor for benchmarks. This allows
 * benchmark code to be run from within a event loop thread, which can be useful when the code is specifically
 * optimized for running inside an event loop. Without this, it would be necessary to switch between threads in
 * the benchmark which adds significant noise to the benchmark.
 *
 * <p>This class is essentially the same as {@link DefaultEventLoopGroup} except it stores
 * a reference to the {@link EventLoop} in a {@link FastThreadLocal} to allow benchmark code to reference it
 * using {@link EventLoopJmhExecutor#currentEventLoop()}.
 *
 * <p>To run on the event loop, the benchmark must specify the JVM args as something like
 * <pre>{@code
 *   {@literal @}Fork(jvmArgsAppend = { EventLoopJmhExecutor.JVM_ARG_1, EventLoopJmhExecutor.JMH_ARG_2 })
 * }</pre>
 */
public class EventLoopJmhExecutor extends MultithreadEventLoopGroup {

    public static final String JVM_ARG_1 = "-Djmh.executor=CUSTOM";
    public static final String JVM_ARG_2 =
            "-Djmh.executor.class=com.linecorp.armeria.shared.EventLoopJmhExecutor";

    private static final FastThreadLocal<EventLoop> CURRENT_EVENT_LOOP = new FastThreadLocal<>();

    public static EventLoop currentEventLoop() {
        return CURRENT_EVENT_LOOP.get();
    }

    public EventLoopJmhExecutor(int numThreads, String threadNamePrefix) {
        super(numThreads, ThreadFactories.builder(threadNamePrefix).eventLoop(true).build());
    }

    @Override
    protected EventLoop newChild(Executor executor, Object... args) throws Exception {
        final EventLoop eventLoop = new DefaultEventLoop(this, executor);
        eventLoop.submit(() -> CURRENT_EVENT_LOOP.set(eventLoop)).syncUninterruptibly();
        return eventLoop;
    }
}

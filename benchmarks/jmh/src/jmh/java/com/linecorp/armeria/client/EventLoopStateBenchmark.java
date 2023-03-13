/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.client;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.util.EventLoopGroups;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

@State(Scope.Benchmark)
public class EventLoopStateBenchmark {
    private AbstractEventLoopState state;
    private AbstractEventLoopEntry[] acquired;
    private EventLoopGroup eventLoopGroup;

    @Param({ "32", "64", "128", "256" })
    private int maxNumEventLoops;
    @Param({ "true", "false" })
    private boolean arrayBased;

    @Setup
    public void setUp() {
        acquired = new AbstractEventLoopEntry[maxNumEventLoops];

        eventLoopGroup = EventLoopGroups.newEventLoopGroup(maxNumEventLoops);
        final DefaultEventLoopScheduler scheduler = new DefaultEventLoopScheduler(
                eventLoopGroup, maxNumEventLoops, maxNumEventLoops, ImmutableList.of());
        final List<EventLoop> eventLoops = Streams.stream(eventLoopGroup)
                                                  .map(EventLoop.class::cast)
                                                  .collect(toImmutableList());
        if (arrayBased) {
            state = new ArrayBasedEventLoopState(eventLoops, maxNumEventLoops, scheduler);
        } else {
            state = new HeapBasedEventLoopState(eventLoops, maxNumEventLoops, scheduler);
        }

        // Acquire as many as the number of eventLoops so that the active request of all states are
        // greater than 1.
        for (int i = 0; i < maxNumEventLoops; i++) {
            state.acquire();
        }
    }

    @TearDown
    public void tearDown() {
        eventLoopGroup.shutdownGracefully();
    }

    @Benchmark
    public void lastInFirstOut() {
        for (int i = 0; i < maxNumEventLoops; ++i) {
            acquired[i] = state.acquire();
        }
        for (int i = maxNumEventLoops - 1; i >= 0; --i) {
            acquired[i].release();
        }
    }

    @Benchmark
    public void firstInFirstOut() {
        for (int i = 0; i < maxNumEventLoops; ++i) {
            acquired[i] = state.acquire();
        }
        for (int i = 0; i < maxNumEventLoops; ++i) {
            acquired[i].release();
        }
    }
}

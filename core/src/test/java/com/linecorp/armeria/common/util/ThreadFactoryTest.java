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

package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.netty.util.concurrent.FastThreadLocalThread;

public class ThreadFactoryTest {

    @Test
    void testEventLoopThreadFactory() {

        final Thread eventLoopThread = ThreadFactories.builderForEventLoops("event-loop-normal")
                                                      .build()
                                                      .newThread(() -> {});

        assertThat(eventLoopThread.getClass()).isSameAs(EventLoopThread.class);
        assertThat(eventLoopThread.getName()).startsWith("event-loop-normal");
        assertThat(eventLoopThread.getPriority()).isEqualTo(Thread.NORM_PRIORITY);
        assertThat(eventLoopThread.isDaemon()).isFalse();

        final Thread eventLoopThreadCustom = ThreadFactories.builderForEventLoops("event-loop-custom")
                                                            .priority(Thread.MAX_PRIORITY)
                                                            .daemon(true)
                                                            .build()
                                                            .newThread(() -> {});

        assertThat(eventLoopThreadCustom.getClass()).isSameAs(EventLoopThread.class);
        assertThat(eventLoopThreadCustom.getName()).startsWith("event-loop-custom");
        assertThat(eventLoopThreadCustom.getPriority()).isEqualTo(Thread.MAX_PRIORITY);
        assertThat(eventLoopThreadCustom.isDaemon()).isTrue();
    }

    @Test
    void testNonEventLoopThreadFactory() {

        final Thread nonEventLoopThread = ThreadFactories.builder("non-event-loop-normal")
                                                         .build()
                                                         .newThread(() -> {});

        assertThat(nonEventLoopThread.getClass()).isSameAs(FastThreadLocalThread.class);
        assertThat(nonEventLoopThread.getName()).startsWith("non-event-loop-normal");
        assertThat(nonEventLoopThread.getPriority()).isEqualTo(Thread.NORM_PRIORITY);
        assertThat(nonEventLoopThread.isDaemon()).isFalse();

        final Thread nonEventLoopThreadCustom = ThreadFactories.builder("non-event-loop-custom")
                                                               .priority(Thread.MAX_PRIORITY)
                                                               .daemon(true)
                                                               .build()
                                                               .newThread(() -> {});

        assertThat(nonEventLoopThreadCustom.getClass()).isSameAs(FastThreadLocalThread.class);
        assertThat(nonEventLoopThreadCustom.getName()).startsWith("non-event-loop-custom");
        assertThat(nonEventLoopThreadCustom.getPriority()).isEqualTo(Thread.MAX_PRIORITY);
        assertThat(nonEventLoopThreadCustom.isDaemon()).isTrue();
    }
}

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
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.netty.util.concurrent.FastThreadLocalThread;

public class ThreadFactoryTest {

    @Test
    void testEventLoopThreadFactory() {

        final ThreadGroup eventLoopThreadGroup = new ThreadGroup("normal-group");
        final Thread eventLoopThread = ThreadFactories.builderForEventLoops("normal-thread")
                                                      .threadGroup(eventLoopThreadGroup)
                                                      .build()
                                                      .newThread(() -> {});

        assertThat(eventLoopThread.getClass()).isSameAs(EventLoopThread.class);
        assertThat(eventLoopThread.getName()).startsWith("normal-thread");
        assertThat(eventLoopThread.getPriority()).isEqualTo(Thread.NORM_PRIORITY);
        assertThat(eventLoopThread.isDaemon()).isFalse();
        assertThat(eventLoopThread.getThreadGroup().getName()).isEqualTo("normal-group");

        final ThreadGroup eventLoopCustomThreadGroup = new ThreadGroup("custom-group");
        final Thread eventLoopCustomThread = ThreadFactories.builderForEventLoops("custom-thread")
                                                            .priority(Thread.MAX_PRIORITY)
                                                            .daemon(true)
                                                            .threadGroup(eventLoopCustomThreadGroup)
                                                            .build()
                                                            .newThread(() -> {});

        assertThat(eventLoopCustomThread.getClass()).isSameAs(EventLoopThread.class);
        assertThat(eventLoopCustomThread.getName()).startsWith("custom-thread");
        assertThat(eventLoopCustomThread.getPriority()).isEqualTo(Thread.MAX_PRIORITY);
        assertThat(eventLoopCustomThread.isDaemon()).isTrue();
        assertThat(eventLoopCustomThread.getThreadGroup().getName()).isEqualTo("custom-group");
    }

    @Test
    void testNonEventLoopThreadFactory() {

        final ThreadGroup nonEventLoopThreadGroup = new ThreadGroup("normal-group");
        final Thread nonEventLoopThread = ThreadFactories.builder("normal-thread")
                                                         .threadGroup(nonEventLoopThreadGroup)
                                                         .build()
                                                         .newThread(() -> {});

        assertThat(nonEventLoopThread.getClass()).isSameAs(FastThreadLocalThread.class);
        assertThat(nonEventLoopThread.getName()).startsWith("normal-thread");
        assertThat(nonEventLoopThread.getPriority()).isEqualTo(Thread.NORM_PRIORITY);
        assertThat(nonEventLoopThread.isDaemon()).isFalse();
        assertThat(nonEventLoopThread.getThreadGroup().getName()).isEqualTo("normal-group");

        final ThreadGroup nonEventLoopCustomThreadGroup = new ThreadGroup("custom-group");
        final Thread nonEventLoopCustomThread = ThreadFactories.builder("custom-thread")
                                                               .priority(Thread.MAX_PRIORITY)
                                                               .daemon(true)
                                                               .threadGroup(nonEventLoopCustomThreadGroup)
                                                               .build()
                                                               .newThread(() -> {});

        assertThat(nonEventLoopCustomThread.getClass()).isSameAs(FastThreadLocalThread.class);
        assertThat(nonEventLoopCustomThread.getName()).startsWith("custom-thread");
        assertThat(nonEventLoopCustomThread.getPriority()).isEqualTo(Thread.MAX_PRIORITY);
        assertThat(nonEventLoopCustomThread.isDaemon()).isTrue();
        assertThat(nonEventLoopCustomThread.getThreadGroup().getName()).isEqualTo("custom-group");
    }

    @Test
    void testTheadPriorityRange() {
        assertThrows(IllegalArgumentException.class, () -> {
           ThreadFactories.builder("priority-test")
                          .priority(-1)
                          .build();
        });
    }
}

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

import com.linecorp.armeria.internal.common.util.EventLoopThread;

import io.netty.util.concurrent.FastThreadLocalThread;

public class ThreadFactoryTest {

    @Test
    void testEventLoopThreadFactory() {

        final ThreadGroup eventLoopThreadGroup = new ThreadGroup("normal-group");
        final Thread eventLoopThread = ThreadFactories.builder("normal-thread")
                                                      .eventLoop(true)
                                                      .threadGroup(eventLoopThreadGroup)
                                                      .build()
                                                      .newThread(() -> {});

        assertThat(eventLoopThread.getClass()).isSameAs(EventLoopThread.class);
        assertThat(eventLoopThread.getName()).startsWith("normal-thread");
        assertThat(eventLoopThread.getPriority()).isEqualTo(Thread.NORM_PRIORITY);
        assertThat(eventLoopThread.isDaemon()).isFalse();
        assertThat(eventLoopThread.getThreadGroup().getName()).isEqualTo("normal-group");

        final ThreadGroup eventLoopCustomThreadGroup = new ThreadGroup("custom-group");
        final Thread eventLoopCustomThread = ThreadFactories.builder("custom-thread")
                                                            .eventLoop(true)
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
        assertThrows(IllegalArgumentException.class, () -> ThreadFactories.builder("priority-lowerbound-test")
                                                                          .priority(Thread.MIN_PRIORITY - 1)
                                                                          .build()
        );

        assertThrows(IllegalArgumentException.class, () -> ThreadFactories.builder("priority-upperbound-test")
                                                                          .priority(Thread.MAX_PRIORITY + 1)
                                                                          .build()
        );
    }

    @Test
    void testAbstractThreadFactory() {
        final Thread t1 = new EventLoopThreadFactory("test").newThread(() -> {});
        assertThat(t1.getName()).startsWith("test");

        final Thread t2 = new EventLoopThreadFactory("test", true).newThread(() -> {});
        assertThat(t2.isDaemon()).isTrue();

        final Thread t3 = new EventLoopThreadFactory("test", Thread.MAX_PRIORITY).newThread(() -> {});
        assertThat(t3.getPriority()).isEqualTo(Thread.MAX_PRIORITY);

        final Thread t4 = new EventLoopThreadFactory("test",
                                               true, Thread.MIN_PRIORITY).newThread(() -> {});
        assertThat(t4.isDaemon()).isTrue();
        assertThat(t4.getPriority()).isEqualTo(Thread.MIN_PRIORITY);

        final ThreadGroup testGroup = new ThreadGroup("test-group");
        final Thread t5 = new EventLoopThreadFactory("test", false,
                                                     Thread.NORM_PRIORITY, testGroup).newThread(() -> {});
        assertThat(t5.isDaemon()).isFalse();
        assertThat(t5.getPriority()).isEqualTo(Thread.NORM_PRIORITY);
        assertThat(t5.getThreadGroup().getName()).isEqualTo("test-group");
    }
}

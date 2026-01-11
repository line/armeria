/*
 * Copyright 2025 LINE Corporation
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.Flags;

import io.netty.channel.EventLoopGroup;

class EventLoopGroupBuilderTest {

    @Test
    void testDefault() {
        final EventLoopGroup group = EventLoopGroups.builder().build();
        assertThat(group).isNotNull();
        // Default should not wrap (uses Netty defaults)
        assertThat(group.getClass().getSimpleName())
                .isNotEqualTo("ShutdownConfigurableEventLoopGroup");
    }

    @Test
    void testDefaultNumThreads() {
        final EventLoopGroup group = EventLoopGroups.builder().build();
        try {
            // Default numThreads should be Flags.numCommonWorkers()
            int count = 0;
            for (Object ignored : group) {
                count++;
            }
            assertThat(count).isEqualTo(Flags.numCommonWorkers());
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    void testCustomNumThreads() {
        final EventLoopGroup group = EventLoopGroups.builder()
                .numThreads(2)
                .build();
        try {
            int count = 0;
            for (Object ignored : group) {
                count++;
            }
            assertThat(count).isEqualTo(2);
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    void testCustomThreadNamePrefix() {
        final EventLoopGroup group = EventLoopGroups.builder()
                .numThreads(1)
                .threadNamePrefix("test-eventloop")
                .build();
        try {
            assertThat(group).isNotNull();
            // Thread name prefix is applied (will be suffixed with transport type)
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    void testUseDaemonThreads() {
        final EventLoopGroup group = EventLoopGroups.builder()
                .numThreads(1)
                .useDaemonThreads(true)
                .build();
        try {
            assertThat(group).isNotNull();
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    void testGracefulShutdownConfigurationWithDuration() {
        final EventLoopGroup group = EventLoopGroups.builder()
                .numThreads(1)
                .gracefulShutdown(Duration.ofSeconds(1), Duration.ofSeconds(5))
                .build();
        try {
            // Wrapper is package-private, just verify it was wrapped
            assertThat(group.getClass().getSimpleName())
                    .isEqualTo("ShutdownConfigurableEventLoopGroup");
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    void testGracefulShutdownConfigurationWithMillis() {
        final EventLoopGroup group = EventLoopGroups.builder()
                .numThreads(1)
                .gracefulShutdownMillis(1000, 5000)
                .build();
        try {
            // Wrapper is package-private, just verify it was wrapped
            assertThat(group.getClass().getSimpleName())
                    .isEqualTo("ShutdownConfigurableEventLoopGroup");
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    void testGracefulShutdownWithDefaultQuietPeriodOnly() {
        // Only changing timeout should still wrap
        final EventLoopGroup group = EventLoopGroups.builder()
                .numThreads(1)
                .gracefulShutdownMillis(2000, 30000) // default quiet period, different timeout
                .build();
        try {
            assertThat(group.getClass().getSimpleName())
                    .isEqualTo("ShutdownConfigurableEventLoopGroup");
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    void testGracefulShutdownWithDefaultTimeoutOnly() {
        // Only changing quiet period should still wrap
        final EventLoopGroup group = EventLoopGroups.builder()
                .numThreads(1)
                .gracefulShutdownMillis(1000, 15000) // different quiet period, default timeout
                .build();
        try {
            assertThat(group.getClass().getSimpleName())
                    .isEqualTo("ShutdownConfigurableEventLoopGroup");
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    void testInvalidNumThreadsZero() {
        assertThatThrownBy(() -> EventLoopGroups.builder().numThreads(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numThreads");
    }

    @Test
    void testInvalidNumThreadsNegative() {
        assertThatThrownBy(() -> EventLoopGroups.builder().numThreads(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numThreads");
    }

    @Test
    void testInvalidShutdownQuietPeriodNegative() {
        assertThatThrownBy(() -> EventLoopGroups.builder()
                .gracefulShutdownMillis(-1, 15000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quietPeriodMillis");
    }

    @Test
    void testInvalidShutdownTimeoutLessThanQuietPeriod() {
        // timeout must be >= quietPeriod
        assertThatThrownBy(() -> EventLoopGroups.builder()
                .gracefulShutdownMillis(5000, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutMillis");
    }

    @Test
    void testNullThreadNamePrefix() {
        assertThatThrownBy(() -> EventLoopGroups.builder().threadNamePrefix(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullThreadFactory() {
        assertThatThrownBy(() -> EventLoopGroups.builder().threadFactory(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullQuietPeriod() {
        assertThatThrownBy(() -> EventLoopGroups.builder()
                .gracefulShutdown(null, Duration.ofSeconds(15)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullTimeout() {
        assertThatThrownBy(() -> EventLoopGroups.builder()
                .gracefulShutdown(Duration.ofSeconds(2), null))
                .isInstanceOf(NullPointerException.class);
    }
}

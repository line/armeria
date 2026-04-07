/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.internal.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.common.util.TransportType;

import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.uring.IoUringChannelOption;

public class ChannelUtilTest {

    public static final int TCP_USER_TIMEOUT_BUFFER_MILLIS = ChannelUtil.TCP_USER_TIMEOUT_BUFFER_MILLIS;

    @Test
    @SuppressWarnings("deprecation")
    void prohibitedOptions() {
        assertThat(ChannelUtil.prohibitedOptions()).containsExactlyInAnyOrder(
                ChannelOption.ALLOW_HALF_CLOSURE, ChannelOption.AUTO_READ,
                ChannelOption.AUTO_CLOSE, ChannelOption.MAX_MESSAGES_PER_READ,
                ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, ChannelOption.WRITE_BUFFER_LOW_WATER_MARK,
                EpollChannelOption.EPOLL_MODE);
    }

    private static Stream<Arguments> tcpUserTimeout_arguments() {
        final Stream.Builder<Arguments> builder = Stream.builder();
        builder.add(Arguments.of(TransportType.EPOLL, EpollChannelOption.TCP_USER_TIMEOUT));
        if (SystemInfo.javaVersion() >= 9) {
            builder.add(Arguments.of(TransportType.IO_URING, IoUringChannelOption.TCP_USER_TIMEOUT));
        }
        return builder.build();
    }

    @Test
    void disabledDefaultChannelOptions() {
        final Map<ChannelOption<?>, Object> options = ImmutableMap.of(
                ChannelOption.SO_LINGER, 1000);

        Map<ChannelOption<?>, Object> newOptions = ChannelUtil.applyDefaultChannelOptions(
                true, TransportType.EPOLL, options, 3000);
        assertThat(options).isNotEqualTo(newOptions);

        newOptions = ChannelUtil.applyDefaultChannelOptions(
                false, TransportType.EPOLL, options, 3000);
        assertThat(options).containsExactlyEntriesOf(newOptions);
    }

    @ParameterizedTest
    @MethodSource("tcpUserTimeout_arguments")
    void tcpUserTimeoutDefaultApplied(TransportType transportType, ChannelOption<?> option) {
        final int lingerMillis = 1000;
        final Map<ChannelOption<?>, Object> options = ImmutableMap.of(
                ChannelOption.SO_LINGER, lingerMillis);

        // ignore if idle timeout not set
        Map<ChannelOption<?>, Object> newOptions =
                ChannelUtil.applyDefaultChannelOptions(
                        true, transportType, options, 0);
        assertThat(options).containsExactlyEntriesOf(newOptions);

        // ignore if idle timeout is out of bounds
        newOptions = ChannelUtil.applyDefaultChannelOptions(
                true, transportType, options, -1);
        assertThat(options).containsExactlyEntriesOf(newOptions);

        // apply idle timeout if possible
        final int idleTimeoutMillis = 2000;
        newOptions = ChannelUtil.applyDefaultChannelOptions(
                true, transportType, options, idleTimeoutMillis);
        assertThat(newOptions).containsOnly(
                entry(ChannelOption.SO_LINGER, lingerMillis),
                entry(option, idleTimeoutMillis + ChannelUtil.TCP_USER_TIMEOUT_BUFFER_MILLIS));

        // user defined options are respected
        final int userDefinedTcpUserTimeoutMillis = 10_000;
        final Map<ChannelOption<?>, Object> userDefinedOptions = ImmutableMap.of(
                ChannelOption.SO_LINGER, lingerMillis, option, userDefinedTcpUserTimeoutMillis);
        newOptions = ChannelUtil.applyDefaultChannelOptions(
                true, transportType, userDefinedOptions, idleTimeoutMillis);
        assertThat(newOptions).containsExactlyInAnyOrderEntriesOf(userDefinedOptions);
    }

    @ParameterizedTest
    @MethodSource("tcpUserTimeout_arguments")
    void tcpUserTimeoutWithMaxIdleTimeout(TransportType transportType, ChannelOption<?> option) {
        final Map<ChannelOption<?>, Object> options = ImmutableMap.of(
                ChannelOption.SO_LINGER, 1000);

        // Long.MAX_VALUE should not cause overflow — should clamp to Integer.MAX_VALUE
        final Map<ChannelOption<?>, Object> newOptions =
                ChannelUtil.applyDefaultChannelOptions(
                        true, transportType, options, Long.MAX_VALUE);
        assertThat(newOptions).containsEntry(option, Integer.MAX_VALUE);
    }

    @Test
    void tcpUserTimeoutUnsupportedTransport() {
        final Map<ChannelOption<?>, Object> options = ImmutableMap.of(
                ChannelOption.SO_LINGER, 1000);

        final Map<ChannelOption<?>, Object> newOptions =
                ChannelUtil.applyDefaultChannelOptions(
                        true, TransportType.NIO, options, 3000);
        assertThat(options).containsExactlyEntriesOf(newOptions);
    }
}

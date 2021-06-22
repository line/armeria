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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.util.TransportType;

import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.incubator.channel.uring.IOUringChannelOption;

public class ChannelUtilTest {

    public static final long TCP_USER_TIMEOUT_BUFFER_MILLIS = ChannelUtil.TCP_USER_TIMEOUT_BUFFER_MILLIS;

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
        return Stream.of(
                Arguments.of(TransportType.EPOLL, EpollChannelOption.TCP_USER_TIMEOUT),
                Arguments.of(TransportType.IO_URING, IOUringChannelOption.TCP_USER_TIMEOUT)
        );
    }

    @Test
    void disabledDefaultChannelOptions() {
        final Map<ChannelOption<?>, Object> options = ImmutableMap.of(
                ChannelOption.SO_LINGER, 1000);

        Map<ChannelOption<?>, Object> newOptions = ChannelUtil.applyDefaultChannelOptions(
                true, TransportType.EPOLL, options, 3000, 4000);
        assertThat(options).isNotEqualTo(newOptions);

        newOptions = ChannelUtil.applyDefaultChannelOptions(
                false, TransportType.EPOLL, options, 3000, 4000);
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
                        true, transportType, options, 0, 0);
        assertThat(options).containsExactlyEntriesOf(newOptions);

        // ignore if idle timeout is out of bounds
        newOptions = ChannelUtil.applyDefaultChannelOptions(
                true, transportType, options,
                Integer.MAX_VALUE - ChannelUtil.TCP_USER_TIMEOUT_BUFFER_MILLIS + 1, 0);
        assertThat(options).containsExactlyEntriesOf(newOptions);

        // apply idle timeout if possible
        final int idleTimeoutMillis = 2000;
        newOptions = ChannelUtil.applyDefaultChannelOptions(
                true, transportType, options, idleTimeoutMillis, 0);
        assertThat(newOptions).containsOnly(
                entry(ChannelOption.SO_LINGER, lingerMillis),
                entry(option, idleTimeoutMillis + ChannelUtil.TCP_USER_TIMEOUT_BUFFER_MILLIS));

        // user defined options are respected
        final long userDefinedTcpUserTimeoutMillis = 10_000;
        final Map<ChannelOption<?>, Object> userDefinedOptions = ImmutableMap.of(
                ChannelOption.SO_LINGER, lingerMillis, option, userDefinedTcpUserTimeoutMillis);
        newOptions = ChannelUtil.applyDefaultChannelOptions(
                true, transportType, userDefinedOptions, idleTimeoutMillis, 0);
        assertThat(newOptions).containsExactlyInAnyOrderEntriesOf(userDefinedOptions);
    }

    @Test
    void tcpUserTimeoutUnsupportedTransport() {
        final Map<ChannelOption<?>, Object> options = ImmutableMap.of(
                ChannelOption.SO_LINGER, 1000);

        final Map<ChannelOption<?>, Object> newOptions =
                ChannelUtil.applyDefaultChannelOptions(
                        true, TransportType.NIO, options, 3000, 4000);
        assertThat(options).containsExactlyEntriesOf(newOptions);
    }

    @ParameterizedTest
    @EnumSource(TransportType.class)
    void keepAliveChannelOption(TransportType type) {
        final int lingerMillis = 1000;
        final Map<ChannelOption<?>, Object> options = ImmutableMap.of(
                ChannelOption.SO_LINGER, lingerMillis);

        // ignore if parameters are infinite
        Map<ChannelOption<?>, Object> newOptions =
                ChannelUtil.applyDefaultChannelOptions(true, type, options, 0, 0);
        assertThat(newOptions).containsExactlyEntriesOf(options);

        // ignore if parameters are out of bounds
        newOptions = ChannelUtil.applyDefaultChannelOptions(
                true, type, options, 0, Long.MAX_VALUE);
        assertThat(newOptions).containsExactlyEntriesOf(options);

        final long pingIntervalMillis = 3000;
        newOptions = ChannelUtil.applyDefaultChannelOptions(
                true, type, options, 0, pingIntervalMillis);
        if (type == TransportType.EPOLL) {
            assertThat(newOptions).containsOnly(entry(ChannelOption.SO_LINGER, lingerMillis),
                                                entry(ChannelOption.SO_KEEPALIVE, true),
                                                entry(EpollChannelOption.TCP_KEEPINTVL, pingIntervalMillis),
                                                entry(EpollChannelOption.TCP_KEEPIDLE, pingIntervalMillis));
        } else if (type == TransportType.IO_URING) {
            assertThat(newOptions).containsOnly(entry(ChannelOption.SO_LINGER, lingerMillis),
                                                entry(ChannelOption.SO_KEEPALIVE, true),
                                                entry(IOUringChannelOption.TCP_KEEPINTVL, pingIntervalMillis),
                                                entry(IOUringChannelOption.TCP_KEEPIDLE, pingIntervalMillis));
        } else {
            assertThat(newOptions).containsExactlyEntriesOf(options);
        }

        // user defined options are respected
        final Map<ChannelOption<?>, Object> userDefinedOptions = ImmutableMap.of(
                ChannelOption.SO_LINGER, lingerMillis, ChannelOption.SO_KEEPALIVE, false);
        newOptions = ChannelUtil.applyDefaultChannelOptions(
                true, type, userDefinedOptions, 0, pingIntervalMillis);
        assertThat(newOptions).containsExactlyInAnyOrderEntriesOf(userDefinedOptions);
    }
}

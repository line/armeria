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

package com.linecorp.armeria.client;

import static com.linecorp.armeria.client.ClientFactoryOptions.ADDRESS_RESOLVER_GROUP_FACTORY;
import static com.linecorp.armeria.client.ClientFactoryOptions.CHANNEL_OPTIONS;
import static com.linecorp.armeria.client.ClientFactoryOptions.CONNECTION_POOL_LISTENER;
import static com.linecorp.armeria.client.ClientFactoryOptions.EVENT_LOOP_SCHEDULER_FACTORY;
import static com.linecorp.armeria.client.ClientFactoryOptions.HTTP1_MAX_CHUNK_SIZE;
import static com.linecorp.armeria.client.ClientFactoryOptions.HTTP1_MAX_HEADER_SIZE;
import static com.linecorp.armeria.client.ClientFactoryOptions.HTTP1_MAX_INITIAL_LINE_LENGTH;
import static com.linecorp.armeria.client.ClientFactoryOptions.HTTP2_INITIAL_CONNECTION_WINDOW_SIZE;
import static com.linecorp.armeria.client.ClientFactoryOptions.HTTP2_INITIAL_STREAM_WINDOW_SIZE;
import static com.linecorp.armeria.client.ClientFactoryOptions.HTTP2_MAX_FRAME_SIZE;
import static com.linecorp.armeria.client.ClientFactoryOptions.HTTP2_MAX_HEADER_LIST_SIZE;
import static com.linecorp.armeria.client.ClientFactoryOptions.IDLE_TIMEOUT_MILLIS;
import static com.linecorp.armeria.client.ClientFactoryOptions.METER_REGISTRY;
import static com.linecorp.armeria.client.ClientFactoryOptions.SHUTDOWN_WORKER_GROUP_ON_CLOSE;
import static com.linecorp.armeria.client.ClientFactoryOptions.USE_HTTP1_PIPELINING;
import static com.linecorp.armeria.client.ClientFactoryOptions.USE_HTTP2_PREFACE;
import static com.linecorp.armeria.client.ClientFactoryOptions.USE_HTTP2_WITHOUT_ALPN;
import static com.linecorp.armeria.client.ClientFactoryOptions.WORKER_GROUP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.util.EventLoopGroups;

import io.micrometer.core.instrument.Metrics;
import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolverGroup;

class ClientFactoryOptionsTest {

    static EventLoopGroup executors;

    @BeforeAll
    static void setUp() {
        executors = EventLoopGroups.newEventLoopGroup(1);
    }

    @AfterAll
    static void tearDown() {
        assertThat(MoreExecutors.shutdownAndAwaitTermination(executors, 10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void testAsMap() {
        final ClientFactoryOptions options = ClientFactoryOptions.of(
                HTTP2_INITIAL_CONNECTION_WINDOW_SIZE.newValue(1));
        final Map<ClientFactoryOption<Object>, ClientFactoryOptionValue<Object>> map = options.asMap();
        assertThat(map).hasSize(1);
        assertThat(map.get(HTTP2_INITIAL_CONNECTION_WINDOW_SIZE).value()).isEqualTo(1);
    }

    @ParameterizedTest
    @ArgumentsSource(ClientFactoryOptionsProvider.class)
    void shouldKeepSpecifiedOption(ClientFactoryOption<Object> option, Object value) {
        final ClientFactoryOptions first = ClientFactoryOptions.of(option.newValue(value));
        final ClientFactoryOptions second = ClientFactoryOptions.of();
        final ClientFactoryOptions merged = ClientFactoryOptions.of(first, second);
        assertThat(merged.get(option)).isEqualTo(value);
    }

    private static class ClientFactoryOptionsProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final Function<? super EventLoopGroup, ? extends EventLoopScheduler> schedulerFactory =
                    eventLoopGroup -> new DefaultEventLoopScheduler(eventLoopGroup, 0, 0, ImmutableList.of());
            final Function<? super EventLoopGroup,
                    ? extends AddressResolverGroup<? extends InetSocketAddress>> addressResolverGroupFactory =
                    eventLoopGroup -> new DnsResolverGroupBuilder().build(eventLoopGroup);

            return Stream.of(
                    arguments(WORKER_GROUP, executors),
                    arguments(SHUTDOWN_WORKER_GROUP_ON_CLOSE, true),
                    arguments(CHANNEL_OPTIONS, ImmutableMap.of()),
                    arguments(EVENT_LOOP_SCHEDULER_FACTORY, schedulerFactory),
                    arguments(ADDRESS_RESOLVER_GROUP_FACTORY, addressResolverGroupFactory),
                    arguments(HTTP2_INITIAL_CONNECTION_WINDOW_SIZE, 1),
                    arguments(HTTP2_INITIAL_STREAM_WINDOW_SIZE, 2),
                    arguments(HTTP2_MAX_FRAME_SIZE, 3),
                    arguments(HTTP2_MAX_HEADER_LIST_SIZE, 4),
                    arguments(HTTP1_MAX_INITIAL_LINE_LENGTH, 5),
                    arguments(HTTP1_MAX_HEADER_SIZE, 6),
                    arguments(HTTP1_MAX_CHUNK_SIZE, 7),
                    arguments(IDLE_TIMEOUT_MILLIS, 8),
                    arguments(USE_HTTP2_PREFACE, true),
                    arguments(USE_HTTP2_WITHOUT_ALPN, false),
                    arguments(USE_HTTP1_PIPELINING, false),
                    arguments(CONNECTION_POOL_LISTENER, ConnectionPoolListener.noop()),
                    arguments(METER_REGISTRY, Metrics.globalRegistry));
        }
    }
}

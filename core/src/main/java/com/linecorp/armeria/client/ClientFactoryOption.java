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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import com.linecorp.armeria.common.util.AbstractOption;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.ConstantPool;

public final class ClientFactoryOption<T> extends AbstractOption<T> {
    @SuppressWarnings("rawtypes")
    private static final ConstantPool pool = new ConstantPool() {
        @Override
        protected ClientFactoryOption<Object> newConstant(int id, String name) {
            return new ClientFactoryOption<>(id, name);
        }
    };

    public static final ClientFactoryOption<EventLoopGroup> WORKER_GROUP = valueOf("WORKER_GROUP");

    public static final ClientFactoryOption<Boolean> SHUTDOWN_WORKER_GROUP_ON_CLOSE =
            valueOf("SHUTDOWN_WORKER_GROUP_ON_CLOSE");

    public static final ClientFactoryOption<Function<? super EventLoopGroup, ? extends EventLoopScheduler>>
            EVENT_LOOP_SCHEDULER_FACTORY = valueOf("EVENT_LOOP_SCHEDULER_FACTORY");

    public static final ClientFactoryOption<Map<ChannelOption<?>, Object>> CHANNEL_OPTIONS =
            valueOf("CHANNEL_OPTIONS");

    public static final ClientFactoryOption<Consumer<? super SslContextBuilder>> SSL_CONTEXT_CUSTOMIZER =
            valueOf("SSL_CONTEXT_CUSTOMIZER");

    public static final ClientFactoryOption<Function<? super EventLoopGroup,
            ? extends AddressResolverGroup<? extends InetSocketAddress>>>
            ADDRESS_RESOLVER_GROUP_FACTORY = valueOf("ADDRESS_RESOLVER_GROUP_FACTORY");

    public static final ClientFactoryOption<Integer> HTTP2_INITIAL_CONNECTION_WINDOW_SIZE =
            valueOf("HTTP2_INITIAL_CONNECTION_WINDOW_SIZE");

    public static final ClientFactoryOption<Integer> HTTP2_INITIAL_STREAM_WINDOW_SIZE =
            valueOf("HTTP2_INITIAL_STREAM_WINDOW_SIZE");

    public static final ClientFactoryOption<Integer> HTTP2_MAX_FRAME_SIZE =
            valueOf("HTTP2_MAX_FRAME_SIZE");

    public static final ClientFactoryOption<Long> HTTP2_MAX_HEADER_LIST_SIZE =
            valueOf("HTTP2_MAX_HEADER_LIST_SIZE");

    public static final ClientFactoryOption<Integer> HTTP1_MAX_INITIAL_LINE_LENGTH =
            valueOf("HTTP1_MAX_INITIAL_LINE_LENGTH");

    public static final ClientFactoryOption<Integer> HTTP1_MAX_HEADER_SIZE = valueOf("HTTP1_MAX_HEADER_SIZE");

    public static final ClientFactoryOption<Integer> HTTP1_MAX_CHUNK_SIZE = valueOf("HTTP1_MAX_CHUNK_SIZE");

    public static final ClientFactoryOption<Long> IDLE_TIMEOUT_MILLIS = valueOf("IDLE_TIMEOUT_MILLIS");

    public static final ClientFactoryOption<Boolean> USE_HTTP2_PREFACE = valueOf("USE_HTTP2_PREFACE");

    public static final ClientFactoryOption<Boolean> USE_HTTP1_PIPELINING = valueOf("USE_HTTP1_PIPELINING");

    public static final ClientFactoryOption<ConnectionPoolListener> CONNECTION_POOL_LISTENER =
            valueOf("CONNECTION_POOL_LISTENER");

    public static final ClientFactoryOption<MeterRegistry> METER_REGISTRY = valueOf("METER_REGISTRY");

    @SuppressWarnings("unchecked")
    public static <T> ClientFactoryOption<T> valueOf(String name) {
        return (ClientFactoryOption<T>) pool.valueOf(name);
    }

    private ClientFactoryOption(int id, String name) {
        super(id, name);
    }

    /**
     * TBW.
     */
    public ClientFactoryOptionValue<T> newValue(T value) {
        requireNonNull(value, "value");
        return new ClientFactoryOptionValue<>(this, value);
    }
}

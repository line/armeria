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
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.ConstantPool;

/**
 * A {@link ClientFactory} option.
 *
 * @param <T> the type of the option value
 */
public final class ClientFactoryOption<T> extends AbstractOption<T> {
    @SuppressWarnings("rawtypes")
    private static final ConstantPool pool = new ConstantPool() {
        @Override
        protected ClientFactoryOption<Object> newConstant(int id, String name) {
            return new ClientFactoryOption<>(id, name);
        }
    };

    /**
     * The worker {@link EventLoopGroup}.
     */
    public static final ClientFactoryOption<EventLoopGroup> WORKER_GROUP = valueOf("WORKER_GROUP");

    /**
     * Whether to shut down the worker {@link EventLoopGroup} when the {@link ClientFactory} is closed.
     */
    public static final ClientFactoryOption<Boolean> SHUTDOWN_WORKER_GROUP_ON_CLOSE =
            valueOf("SHUTDOWN_WORKER_GROUP_ON_CLOSE");

    /**
     * The factory that creates an {@link EventLoopScheduler} which is responsible for assigning an
     * {@link EventLoop} to handle a connection to the specified {@link Endpoint}.
     */
    public static final ClientFactoryOption<Function<? super EventLoopGroup, ? extends EventLoopScheduler>>
            EVENT_LOOP_SCHEDULER_FACTORY = valueOf("EVENT_LOOP_SCHEDULER_FACTORY");

    /**
     * The {@link ChannelOption}s of the sockets created by the {@link ClientFactory}.
     */
    public static final ClientFactoryOption<Map<ChannelOption<?>, Object>> CHANNEL_OPTIONS =
            valueOf("CHANNEL_OPTIONS");

    /**
     * The {@link Consumer} which can arbitrarily configure the {@link SslContextBuilder} that will be
     * applied to the SSL session.
     */
    public static final ClientFactoryOption<Consumer<? super SslContextBuilder>> TLS_CUSTOMIZER =
            valueOf("TLS_CUSTOMIZER");

    /**
     * The {@link Consumer} which can arbitrarily configure the {@link SslContextBuilder} that will be
     * applied to the SSL session.
     *
     * @deprecated Use {@link #TLS_CUSTOMIZER}.
     */
    @Deprecated
    public static final ClientFactoryOption<Consumer<? super SslContextBuilder>> SSL_CONTEXT_CUSTOMIZER =
            TLS_CUSTOMIZER;

    /**
     * The factory that creates an {@link AddressResolverGroup} which resolves remote addresses into
     * {@link InetSocketAddress}es.
     */
    public static final ClientFactoryOption<Function<? super EventLoopGroup,
            ? extends AddressResolverGroup<? extends InetSocketAddress>>>
            ADDRESS_RESOLVER_GROUP_FACTORY = valueOf("ADDRESS_RESOLVER_GROUP_FACTORY");

    /**
     * The HTTP/2 <a href="https://tools.ietf.org/html/rfc7540#section-6.9.2">initial connection flow-control
     * window size</a>.
     */
    public static final ClientFactoryOption<Integer> HTTP2_INITIAL_CONNECTION_WINDOW_SIZE =
            valueOf("HTTP2_INITIAL_CONNECTION_WINDOW_SIZE");

    /**
     * The <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_INITIAL_WINDOW_SIZE</a>
     * for HTTP/2 stream-level flow control.
     */
    public static final ClientFactoryOption<Integer> HTTP2_INITIAL_STREAM_WINDOW_SIZE =
            valueOf("HTTP2_INITIAL_STREAM_WINDOW_SIZE");

    /**
     * The <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_MAX_FRAME_SIZE</a>
     * that indicates the size of the largest frame payload that this client is willing to receive.
     */
    public static final ClientFactoryOption<Integer> HTTP2_MAX_FRAME_SIZE =
            valueOf("HTTP2_MAX_FRAME_SIZE");

    /**
     * The HTTP/2 <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_MAX_HEADER_LIST_SIZE</a>
     * that indicates the maximum size of header list that the client is prepared to accept, in octets.
     */
    public static final ClientFactoryOption<Long> HTTP2_MAX_HEADER_LIST_SIZE =
            valueOf("HTTP2_MAX_HEADER_LIST_SIZE");

    /**
     * The maximum length of an HTTP/1 response initial line.
     */
    public static final ClientFactoryOption<Integer> HTTP1_MAX_INITIAL_LINE_LENGTH =
            valueOf("HTTP1_MAX_INITIAL_LINE_LENGTH");

    /**
     * The maximum length of all headers in an HTTP/1 response.
     */
    public static final ClientFactoryOption<Integer> HTTP1_MAX_HEADER_SIZE = valueOf("HTTP1_MAX_HEADER_SIZE");

    /**
     * The maximum length of each chunk in an HTTP/1 response content.
     */
    public static final ClientFactoryOption<Integer> HTTP1_MAX_CHUNK_SIZE = valueOf("HTTP1_MAX_CHUNK_SIZE");

    /**
     * The idle timeout of a socket connection in milliseconds.
     */
    public static final ClientFactoryOption<Long> IDLE_TIMEOUT_MILLIS = valueOf("IDLE_TIMEOUT_MILLIS");

    /**
     * Whether to send an HTTP/2 preface string instead of an HTTP/1 upgrade request to negotiate
     * the protocol version of a cleartext HTTP connection.
     */
    public static final ClientFactoryOption<Boolean> USE_HTTP2_PREFACE = valueOf("USE_HTTP2_PREFACE");

    /**
     * Whether to use <a href="https://en.wikipedia.org/wiki/HTTP_pipelining">HTTP pipelining</a> for
     * HTTP/1 connections.
     */
    public static final ClientFactoryOption<Boolean> USE_HTTP1_PIPELINING = valueOf("USE_HTTP1_PIPELINING");

    /**
     * The listener which is notified on a connection pool event.
     */
    public static final ClientFactoryOption<ConnectionPoolListener> CONNECTION_POOL_LISTENER =
            valueOf("CONNECTION_POOL_LISTENER");

    /**
     * The {@link MeterRegistry} which collects various stats.
     */
    public static final ClientFactoryOption<MeterRegistry> METER_REGISTRY = valueOf("METER_REGISTRY");

    /**
     * Returns the {@link ClientFactoryOption} of the specified name.
     */
    @SuppressWarnings("unchecked")
    public static <T> ClientFactoryOption<T> valueOf(String name) {
        return (ClientFactoryOption<T>) pool.valueOf(name);
    }

    /**
     * Creates a new {@link ClientFactoryOption} of the specified unique {@code name}.
     */
    private ClientFactoryOption(int id, String name) {
        super(id, name);
    }

    /**
     * Creates a new value of this option.
     */
    public ClientFactoryOptionValue<T> newValue(T value) {
        requireNonNull(value, "value");
        return new ClientFactoryOptionValue<>(this, value);
    }
}

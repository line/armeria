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

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import com.linecorp.armeria.client.proxy.ProxyConfig;
import com.linecorp.armeria.client.proxy.ProxyConfigSelector;
import com.linecorp.armeria.common.util.AbstractOption;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.AddressResolverGroup;

/**
 * A {@link ClientFactory} option.
 *
 * @param <T> the type of the option value
 */
public final class ClientFactoryOption<T>
        extends AbstractOption<ClientFactoryOption<T>, ClientFactoryOptionValue<T>, T> {

    /**
     * The worker {@link EventLoopGroup}.
     *
     * @deprecated Use {@link ClientFactoryOptions#WORKER_GROUP}.
     */
    @Deprecated
    public static final ClientFactoryOption<EventLoopGroup> WORKER_GROUP = ClientFactoryOptions.WORKER_GROUP;

    /**
     * Whether to shut down the worker {@link EventLoopGroup} when the {@link ClientFactory} is closed.
     *
     * @deprecated Use {@link ClientFactoryOptions#SHUTDOWN_WORKER_GROUP_ON_CLOSE}.
     */
    @Deprecated
    public static final ClientFactoryOption<Boolean> SHUTDOWN_WORKER_GROUP_ON_CLOSE =
            ClientFactoryOptions.SHUTDOWN_WORKER_GROUP_ON_CLOSE;

    /**
     * The factory that creates an {@link EventLoopScheduler} which is responsible for assigning an
     * {@link EventLoop} to handle a connection to the specified {@link Endpoint}.
     *
     * @deprecated Use {@link ClientFactoryOptions#EVENT_LOOP_SCHEDULER_FACTORY}.
     */
    @Deprecated
    public static final ClientFactoryOption<Function<? super EventLoopGroup, ? extends EventLoopScheduler>>
            EVENT_LOOP_SCHEDULER_FACTORY = ClientFactoryOptions.EVENT_LOOP_SCHEDULER_FACTORY;

    /**
     * The {@link ChannelOption}s of the sockets created by the {@link ClientFactory}.
     *
     * @deprecated Use {@link ClientFactoryOptions#CHANNEL_OPTIONS}.
     */
    @Deprecated
    public static final ClientFactoryOption<Map<ChannelOption<?>, Object>> CHANNEL_OPTIONS =
            ClientFactoryOptions.CHANNEL_OPTIONS;

    /**
     * The {@link Consumer} which can arbitrarily configure the {@link SslContextBuilder} that will be
     * applied to the SSL session.
     *
     * @deprecated Use {@link ClientFactoryOptions#TLS_CUSTOMIZER}.
     */
    @Deprecated
    public static final ClientFactoryOption<Consumer<? super SslContextBuilder>> TLS_CUSTOMIZER =
            ClientFactoryOptions.TLS_CUSTOMIZER;

    /**
     * The factory that creates an {@link AddressResolverGroup} which resolves remote addresses into
     * {@link InetSocketAddress}es.
     *
     * @deprecated Use {@link ClientFactoryOptions#ADDRESS_RESOLVER_GROUP_FACTORY}.
     */
    @Deprecated
    public static final ClientFactoryOption<Function<? super EventLoopGroup,
            ? extends AddressResolverGroup<? extends InetSocketAddress>>> ADDRESS_RESOLVER_GROUP_FACTORY =
            ClientFactoryOptions.ADDRESS_RESOLVER_GROUP_FACTORY;

    /**
     * The HTTP/2 <a href="https://tools.ietf.org/html/rfc7540#section-6.9.2">initial connection flow-control
     * window size</a>.
     *
     * @deprecated Use {@link ClientFactoryOptions#HTTP2_INITIAL_CONNECTION_WINDOW_SIZE}.
     */
    @Deprecated
    public static final ClientFactoryOption<Integer> HTTP2_INITIAL_CONNECTION_WINDOW_SIZE =
            ClientFactoryOptions.HTTP2_INITIAL_CONNECTION_WINDOW_SIZE;

    /**
     * The <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_INITIAL_WINDOW_SIZE</a>
     * for HTTP/2 stream-level flow control.
     *
     * @deprecated Use {@link ClientFactoryOptions#HTTP2_INITIAL_STREAM_WINDOW_SIZE}.
     */
    @Deprecated
    public static final ClientFactoryOption<Integer> HTTP2_INITIAL_STREAM_WINDOW_SIZE =
            ClientFactoryOptions.HTTP2_INITIAL_STREAM_WINDOW_SIZE;

    /**
     * The <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_MAX_FRAME_SIZE</a>
     * that indicates the size of the largest frame payload that this client is willing to receive.
     *
     * @deprecated Use {@link ClientFactoryOptions#HTTP2_MAX_FRAME_SIZE}.
     */
    @Deprecated
    public static final ClientFactoryOption<Integer> HTTP2_MAX_FRAME_SIZE =
            ClientFactoryOptions.HTTP2_MAX_FRAME_SIZE;

    /**
     * The HTTP/2 <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_MAX_HEADER_LIST_SIZE</a>
     * that indicates the maximum size of header list that the client is prepared to accept, in octets.
     *
     * @deprecated Use {@link ClientFactoryOptions#HTTP2_MAX_HEADER_LIST_SIZE}.
     */
    @Deprecated
    public static final ClientFactoryOption<Long> HTTP2_MAX_HEADER_LIST_SIZE =
            ClientFactoryOptions.HTTP2_MAX_HEADER_LIST_SIZE;

    /**
     * The maximum length of an HTTP/1 response initial line.
     *
     * @deprecated Use {@link ClientFactoryOptions#HTTP1_MAX_INITIAL_LINE_LENGTH}.
     */
    @Deprecated
    public static final ClientFactoryOption<Integer> HTTP1_MAX_INITIAL_LINE_LENGTH =
            ClientFactoryOptions.HTTP1_MAX_INITIAL_LINE_LENGTH;

    /**
     * The maximum length of all headers in an HTTP/1 response.
     *
     * @deprecated Use {@link ClientFactoryOptions#HTTP1_MAX_HEADER_SIZE}.
     */
    @Deprecated
    public static final ClientFactoryOption<Integer> HTTP1_MAX_HEADER_SIZE =
            ClientFactoryOptions.HTTP1_MAX_HEADER_SIZE;

    /**
     * The maximum length of each chunk in an HTTP/1 response content.
     *
     * @deprecated Use {@link ClientFactoryOptions#HTTP1_MAX_CHUNK_SIZE}.
     */
    @Deprecated
    public static final ClientFactoryOption<Integer> HTTP1_MAX_CHUNK_SIZE =
            ClientFactoryOptions.HTTP1_MAX_CHUNK_SIZE;

    /**
     * The idle timeout of a socket connection in milliseconds.
     *
     * @deprecated Use {@link ClientFactoryOptions#IDLE_TIMEOUT_MILLIS}.
     */
    @Deprecated
    public static final ClientFactoryOption<Long> IDLE_TIMEOUT_MILLIS =
            ClientFactoryOptions.IDLE_TIMEOUT_MILLIS;

    /**
     * The PING interval in milliseconds.
     * When neither read nor write was performed for the specified period of time,
     * a <a href="https://httpwg.org/specs/rfc7540.html#PING">PING</a> frame is sent for HTTP/2 or
     * an <a herf="https://tools.ietf.org/html/rfc7231#section-4.3.7">OPTIONS</a> request with an asterisk ("*")
     * is sent for HTTP/1.
     *
     * @deprecated Use {@link ClientFactoryOptions#PING_INTERVAL_MILLIS}.
     */
    @Deprecated
    public static final ClientFactoryOption<Long> PING_INTERVAL_MILLIS =
            ClientFactoryOptions.PING_INTERVAL_MILLIS;

    /**
     * Whether to send an HTTP/2 preface string instead of an HTTP/1 upgrade request to negotiate
     * the protocol version of a cleartext HTTP connection.
     *
     * @deprecated Use {@link ClientFactoryOptions#USE_HTTP2_PREFACE}.
     */
    @Deprecated
    public static final ClientFactoryOption<Boolean> USE_HTTP2_PREFACE =
            ClientFactoryOptions.USE_HTTP2_PREFACE;

    /**
     * Whether to use <a href="https://en.wikipedia.org/wiki/HTTP_pipelining">HTTP pipelining</a> for
     * HTTP/1 connections.
     *
     * @deprecated Use {@link ClientFactoryOptions#USE_HTTP1_PIPELINING}.
     */
    @Deprecated
    public static final ClientFactoryOption<Boolean> USE_HTTP1_PIPELINING =
            ClientFactoryOptions.USE_HTTP1_PIPELINING;

    /**
     * The listener which is notified on a connection pool event.
     *
     * @deprecated Use {@link ClientFactoryOptions#CONNECTION_POOL_LISTENER}.
     */
    @Deprecated
    public static final ClientFactoryOption<ConnectionPoolListener> CONNECTION_POOL_LISTENER =
            ClientFactoryOptions.CONNECTION_POOL_LISTENER;

    /**
     * The {@link MeterRegistry} which collects various stats.
     *
     * @deprecated Use {@link ClientFactoryOptions#METER_REGISTRY}.
     */
    @Deprecated
    public static final ClientFactoryOption<MeterRegistry> METER_REGISTRY =
            ClientFactoryOptions.METER_REGISTRY;

    /**
     * The {@link ProxyConfigSelector} which determines the {@link ProxyConfig} to be used.
     *
     * @deprecated Use {@link ClientFactoryOptions#PROXY_CONFIG_SELECTOR}.
     */
    @Deprecated
    public static final ClientFactoryOption<ProxyConfigSelector> PROXY_CONFIG_SELECTOR =
            ClientFactoryOptions.PROXY_CONFIG_SELECTOR;

    /**
     * Returns the all available {@link ClientFactoryOption}s.
     */
    public static Set<ClientFactoryOption<?>> allOptions() {
        return allOptions(ClientFactoryOption.class);
    }

    /**
     * Returns the {@link ClientFactoryOption} with the specified {@code name}.
     *
     * @throws NoSuchElementException if there's no such option defined.
     */
    public static ClientFactoryOption<?> of(String name) {
        return of(ClientFactoryOption.class, name);
    }

    /**
     * Defines a new {@link ClientFactoryOption} of the specified name and default value.
     *
     * @param name the name of the option.
     * @param defaultValue the default value of the option, which will be used when unspecified.
     *
     * @throws IllegalStateException if an option with the specified name exists already.
     */
    public static <T> ClientFactoryOption<T> define(String name, T defaultValue) {
        return define(name, defaultValue, Function.identity(), (oldValue, newValue) -> newValue);
    }

    /**
     * Defines a new {@link ClientFactoryOption} of the specified name, default value and merge function.
     *
     * @param name the name of the option.
     * @param defaultValue the default value of the option, which will be used when unspecified.
     * @param validator the {@link Function} which is used for validating and normalizing an option value.
     * @param mergeFunction the {@link BiFunction} which is used for merging old and new option values.
     *
     * @throws IllegalStateException if an option with the specified name exists already.
     */
    public static <T> ClientFactoryOption<T> define(
            String name,
            T defaultValue,
            Function<T, T> validator,
            BiFunction<ClientFactoryOptionValue<T>,
                    ClientFactoryOptionValue<T>,
                    ClientFactoryOptionValue<T>> mergeFunction) {
        return define(ClientFactoryOption.class, name, defaultValue,
                      ClientFactoryOption::new, validator, mergeFunction);
    }

    private ClientFactoryOption(
            String name,
            T defaultValue,
            Function<T, T> validator,
            BiFunction<ClientFactoryOptionValue<T>,
                    ClientFactoryOptionValue<T>,
                    ClientFactoryOptionValue<T>> mergeFunction) {
        super(name, defaultValue, validator, mergeFunction);
    }

    @Override
    protected ClientFactoryOptionValue<T> doNewValue(T value) {
        return new ClientFactoryOptionValue<>(this, value);
    }
}

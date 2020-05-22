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

import static com.google.common.base.Preconditions.checkArgument;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.proxy.ProxyConfigSelector;
import com.linecorp.armeria.client.proxy.ProxyConfigSelector.WrappingProxyConfigSelector;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.util.AbstractOption;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollChannelOption;
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
     */
    public static final ClientFactoryOption<EventLoopGroup> WORKER_GROUP =
            define("WORKER_GROUP", CommonPools.workerGroup());

    /**
     * Whether to shut down the worker {@link EventLoopGroup} when the {@link ClientFactory} is closed.
     */
    public static final ClientFactoryOption<Boolean> SHUTDOWN_WORKER_GROUP_ON_CLOSE =
            define("SHUTDOWN_WORKER_GROUP_ON_CLOSE", false);

    /**
     * The factory that creates an {@link EventLoopScheduler} which is responsible for assigning an
     * {@link EventLoop} to handle a connection to the specified {@link Endpoint}.
     */
    public static final ClientFactoryOption<Function<? super EventLoopGroup, ? extends EventLoopScheduler>>
            EVENT_LOOP_SCHEDULER_FACTORY = define(
            "EVENT_LOOP_SCHEDULER_FACTORY",
            eventLoopGroup -> new DefaultEventLoopScheduler(eventLoopGroup, 0, 0, ImmutableList.of()));

    // Do not accept 1) the options that may break Armeria and 2) the deprecated options.
    @SuppressWarnings("deprecation")
    private static final Set<ChannelOption<?>> PROHIBITED_SOCKET_OPTIONS = ImmutableSet.of(
            ChannelOption.ALLOW_HALF_CLOSURE, ChannelOption.AUTO_READ,
            ChannelOption.AUTO_CLOSE, ChannelOption.MAX_MESSAGES_PER_READ,
            ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, ChannelOption.WRITE_BUFFER_LOW_WATER_MARK,
            EpollChannelOption.EPOLL_MODE);

    /**
     * The {@link ChannelOption}s of the sockets created by the {@link ClientFactory}.
     */
    public static final ClientFactoryOption<Map<ChannelOption<?>, Object>> CHANNEL_OPTIONS =
            define("CHANNEL_OPTIONS", ImmutableMap.of(), newOptions -> {
                for (ChannelOption<?> channelOption : PROHIBITED_SOCKET_OPTIONS) {
                    checkArgument(!newOptions.containsKey(channelOption),
                                  "prohibited channel option: %s", channelOption);
                }
                return newOptions;
            }, (oldValue, newValue) -> {
                final Map<ChannelOption<?>, Object> newOptions = newValue.value();
                if (newOptions.isEmpty()) {
                    return oldValue;
                }
                final Map<ChannelOption<?>, Object> oldOptions = oldValue.value();
                if (oldOptions.isEmpty()) {
                    return newValue;
                }
                final ImmutableMap.Builder<ChannelOption<?>, Object> builder =
                        ImmutableMap.builderWithExpectedSize(oldOptions.size() + newOptions.size());
                oldOptions.forEach((key, value) -> {
                    if (!newOptions.containsKey(key)) {
                        builder.put(key, value);
                    }
                });
                builder.putAll(newOptions);
                return newValue.option().newValue(builder.build());
            });

    /**
     * The {@link Consumer} which can arbitrarily configure the {@link SslContextBuilder} that will be
     * applied to the SSL session.
     */
    public static final ClientFactoryOption<Consumer<? super SslContextBuilder>> TLS_CUSTOMIZER =
            define("TLS_CUSTOMIZER", b -> { /* no-op */ });

    /**
     * The factory that creates an {@link AddressResolverGroup} which resolves remote addresses into
     * {@link InetSocketAddress}es.
     */
    public static final ClientFactoryOption<Function<? super EventLoopGroup,
            ? extends AddressResolverGroup<? extends InetSocketAddress>>> ADDRESS_RESOLVER_GROUP_FACTORY =
            define("ADDRESS_RESOLVER_GROUP_FACTORY",
                   eventLoopGroup -> new DnsResolverGroupBuilder().build(eventLoopGroup));

    /**
     * The HTTP/2 <a href="https://tools.ietf.org/html/rfc7540#section-6.9.2">initial connection flow-control
     * window size</a>.
     */
    public static final ClientFactoryOption<Integer> HTTP2_INITIAL_CONNECTION_WINDOW_SIZE =
            define("HTTP2_INITIAL_CONNECTION_WINDOW_SIZE", Flags.defaultHttp2InitialConnectionWindowSize());

    /**
     * The <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_INITIAL_WINDOW_SIZE</a>
     * for HTTP/2 stream-level flow control.
     */
    public static final ClientFactoryOption<Integer> HTTP2_INITIAL_STREAM_WINDOW_SIZE =
            define("HTTP2_INITIAL_STREAM_WINDOW_SIZE", Flags.defaultHttp2InitialStreamWindowSize());

    /**
     * The <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_MAX_FRAME_SIZE</a>
     * that indicates the size of the largest frame payload that this client is willing to receive.
     */
    public static final ClientFactoryOption<Integer> HTTP2_MAX_FRAME_SIZE =
            define("HTTP2_MAX_FRAME_SIZE", Flags.defaultHttp2MaxFrameSize());

    /**
     * The HTTP/2 <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_MAX_HEADER_LIST_SIZE</a>
     * that indicates the maximum size of header list that the client is prepared to accept, in octets.
     */
    public static final ClientFactoryOption<Long> HTTP2_MAX_HEADER_LIST_SIZE =
            define("HTTP2_MAX_HEADER_LIST_SIZE", Flags.defaultHttp2MaxHeaderListSize());

    /**
     * The maximum length of an HTTP/1 response initial line.
     */
    public static final ClientFactoryOption<Integer> HTTP1_MAX_INITIAL_LINE_LENGTH =
            define("HTTP1_MAX_INITIAL_LINE_LENGTH", Flags.defaultHttp1MaxInitialLineLength());

    /**
     * The maximum length of all headers in an HTTP/1 response.
     */
    public static final ClientFactoryOption<Integer> HTTP1_MAX_HEADER_SIZE =
            define("HTTP1_MAX_HEADER_SIZE", Flags.defaultHttp1MaxHeaderSize());

    /**
     * The maximum length of each chunk in an HTTP/1 response content.
     */
    public static final ClientFactoryOption<Integer> HTTP1_MAX_CHUNK_SIZE =
            define("HTTP1_MAX_CHUNK_SIZE", Flags.defaultHttp1MaxChunkSize());

    /**
     * The idle timeout of a socket connection in milliseconds.
     */
    public static final ClientFactoryOption<Long> IDLE_TIMEOUT_MILLIS =
            define("IDLE_TIMEOUT_MILLIS", Flags.defaultClientIdleTimeoutMillis());

    /**
     * The PING interval in milliseconds.
     * When neither read nor write was performed for the specified period of time,
     * a <a href="https://httpwg.org/specs/rfc7540.html#PING">PING</a> frame is sent for HTTP/2 or
     * an <a herf="https://tools.ietf.org/html/rfc7231#section-4.3.7">OPTIONS</a> request with an asterisk ("*")
     * is sent for HTTP/1.
     */
    public static final ClientFactoryOption<Long> PING_INTERVAL_MILLIS =
            define("PING_INTERVAL_MILLIS", Flags.defaultPingIntervalMillis());

    /**
     * Whether to send an HTTP/2 preface string instead of an HTTP/1 upgrade request to negotiate
     * the protocol version of a cleartext HTTP connection.
     */
    public static final ClientFactoryOption<Boolean> USE_HTTP2_PREFACE =
            define("USE_HTTP2_PREFACE", Flags.defaultUseHttp2Preface());

    /**
     * Whether to use <a href="https://en.wikipedia.org/wiki/HTTP_pipelining">HTTP pipelining</a> for
     * HTTP/1 connections.
     */
    public static final ClientFactoryOption<Boolean> USE_HTTP1_PIPELINING =
            define("USE_HTTP1_PIPELINING", Flags.defaultUseHttp1Pipelining());

    /**
     * The listener which is notified on a connection pool event.
     */
    public static final ClientFactoryOption<ConnectionPoolListener> CONNECTION_POOL_LISTENER =
            define("CONNECTION_POOL_LISTENER", ConnectionPoolListener.noop());

    /**
     * The {@link MeterRegistry} which collects various stats.
     */
    public static final ClientFactoryOption<MeterRegistry> METER_REGISTRY =
            define("METER_REGISTRY", Metrics.globalRegistry);

    /**
     * TODO: add javadoc.
     */
    public static final ClientFactoryOption<ProxyConfigSelector> PROXY_CONFIG_SELECTOR =
            define("PROXY_CONFIG_SELECTOR", WrappingProxyConfigSelector.of(ProxySelector.getDefault()));

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

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
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.util.AbstractOptions;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.AddressResolverGroup;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;

/**
 * A set of {@link ClientFactoryOption}s and their respective values.
 */
@SuppressWarnings("OptionalGetWithoutIsPresent")
public final class ClientFactoryOptions extends AbstractOptions {
    private static final EventLoopGroup DEFAULT_WORKER_GROUP = CommonPools.workerGroup();

    private static final Function<? super EventLoopGroup, ? extends EventLoopScheduler>
            DEFAULT_EVENT_LOOP_SCHEDULER_FACTORY =
            eventLoopGroup -> new DefaultEventLoopScheduler(eventLoopGroup, 0, 0, ImmutableList.of());

    static final Consumer<SslContextBuilder> DEFAULT_TLS_CUSTOMIZER = b -> { /* no-op */ };

    private static final Function<? super EventLoopGroup,
            ? extends AddressResolverGroup<? extends InetSocketAddress>>
            DEFAULT_ADDRESS_RESOLVER_GROUP_FACTORY =
            eventLoopGroup -> new DnsResolverGroupBuilder().build(eventLoopGroup);

    private static final ConnectionPoolListener DEFAULT_CONNECTION_POOL_LISTENER =
            ConnectionPoolListener.noop();

    // Do not accept 1) the options that may break Armeria and 2) the deprecated options.
    @SuppressWarnings("deprecation")
    private static final Set<ChannelOption<?>> PROHIBITED_SOCKET_OPTIONS = ImmutableSet.of(
            ChannelOption.ALLOW_HALF_CLOSURE, ChannelOption.AUTO_READ,
            ChannelOption.AUTO_CLOSE, ChannelOption.MAX_MESSAGES_PER_READ,
            ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, ChannelOption.WRITE_BUFFER_LOW_WATER_MARK,
            EpollChannelOption.EPOLL_MODE);

    private static final ClientFactoryOptionValue<?>[] DEFAULT_OPTIONS = {
            ClientFactoryOption.WORKER_GROUP.newValue(DEFAULT_WORKER_GROUP),
            ClientFactoryOption.SHUTDOWN_WORKER_GROUP_ON_CLOSE.newValue(false),
            ClientFactoryOption.EVENT_LOOP_SCHEDULER_FACTORY.newValue(DEFAULT_EVENT_LOOP_SCHEDULER_FACTORY),
            ClientFactoryOption.CHANNEL_OPTIONS.newValue(new Object2ObjectArrayMap<>()),
            ClientFactoryOption.TLS_CUSTOMIZER.newValue(DEFAULT_TLS_CUSTOMIZER),
            ClientFactoryOption.ADDRESS_RESOLVER_GROUP_FACTORY.newValue(DEFAULT_ADDRESS_RESOLVER_GROUP_FACTORY),
            ClientFactoryOption.HTTP2_INITIAL_CONNECTION_WINDOW_SIZE.newValue(
                    Flags.defaultHttp2InitialConnectionWindowSize()),
            ClientFactoryOption.HTTP2_INITIAL_STREAM_WINDOW_SIZE.newValue(
                    Flags.defaultHttp2InitialStreamWindowSize()),
            ClientFactoryOption.HTTP2_MAX_FRAME_SIZE.newValue(Flags.defaultHttp2MaxFrameSize()),
            ClientFactoryOption.HTTP2_MAX_HEADER_LIST_SIZE.newValue(Flags.defaultHttp2MaxHeaderListSize()),
            ClientFactoryOption.HTTP1_MAX_INITIAL_LINE_LENGTH.newValue(
                    Flags.defaultHttp1MaxInitialLineLength()),
            ClientFactoryOption.HTTP1_MAX_HEADER_SIZE.newValue(Flags.defaultHttp1MaxHeaderSize()),
            ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE.newValue(Flags.defaultHttp1MaxChunkSize()),
            ClientFactoryOption.IDLE_TIMEOUT_MILLIS.newValue(Flags.defaultClientIdleTimeoutMillis()),
            ClientFactoryOption.USE_HTTP2_PREFACE.newValue(Flags.defaultUseHttp2Preface()),
            ClientFactoryOption.USE_HTTP1_PIPELINING.newValue(Flags.defaultUseHttp1Pipelining()),
            ClientFactoryOption.CONNECTION_POOL_LISTENER.newValue(DEFAULT_CONNECTION_POOL_LISTENER),
            ClientFactoryOption.METER_REGISTRY.newValue(Metrics.globalRegistry)
    };

    private static final ClientFactoryOptions DEFAULT = new ClientFactoryOptions(DEFAULT_OPTIONS);

    /**
     * The default {@link ClientFactoryOptions}.
     */
    public static ClientFactoryOptions of() {
        return DEFAULT;
    }

    /**
     * Returns the {@link ClientFactoryOptions} with the specified {@link ClientFactoryOptionValue}s.
     */
    public static ClientFactoryOptions of(ClientFactoryOptionValue<?>... options) {
        return of(of(), requireNonNull(options, "options"));
    }

    /**
     * Returns the {@link ClientFactoryOptions} with the specified {@link ClientFactoryOptionValue}s.
     */
    public static ClientFactoryOptions of(Iterable<ClientFactoryOptionValue<?>> options) {
        return of(of(), requireNonNull(options, "options"));
    }

    /**
     * Merges the specified {@link ClientFactoryOptions} and {@link ClientFactoryOptionValue}s.
     *
     * @return the merged {@link ClientFactoryOptions}
     */
    public static ClientFactoryOptions of(ClientFactoryOptions baseOptions,
                                          ClientFactoryOptionValue<?>... options) {

        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(options, "options");
        if (options.length == 0) {
            return baseOptions;
        }
        return new ClientFactoryOptions(baseOptions, options);
    }

    /**
     * Merges the specified {@link ClientFactoryOptions} and {@link ClientFactoryOptionValue}s.
     *
     * @return the merged {@link ClientFactoryOptions}
     */
    public static ClientFactoryOptions of(ClientFactoryOptions baseOptions,
                                          Iterable<ClientFactoryOptionValue<?>> options) {

        // TODO: Reduce the cost of creating a derived ClientFactoryOptions.
        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(options, "options");
        if (Iterables.isEmpty(options)) {
            return baseOptions;
        }
        return new ClientFactoryOptions(baseOptions, options);
    }

    /**
     * Merges the specified two {@link ClientFactoryOptions}s.
     *
     * @return the merged {@link ClientFactoryOptions}
     */
    public static ClientFactoryOptions of(ClientFactoryOptions baseOptions, ClientFactoryOptions options) {
        // TODO: Reduce the cost of creating a derived ClientFactoryOptions.
        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(options, "options");
        return new ClientFactoryOptions(baseOptions, options);
    }

    private static <T> ClientFactoryOptionValue<T> filterValue(ClientFactoryOptionValue<T> optionValue) {
        requireNonNull(optionValue, "optionValue");

        final ClientFactoryOption<?> option = optionValue.option();
        final T value = optionValue.value();

        if (option == ClientFactoryOption.CHANNEL_OPTIONS) {
            @SuppressWarnings("unchecked")
            final ClientFactoryOption<Map<ChannelOption<?>, Object>> castOption =
                    (ClientFactoryOption<Map<ChannelOption<?>, Object>>) option;
            @SuppressWarnings("unchecked")
            final ClientFactoryOptionValue<T> castOptionValue =
                    (ClientFactoryOptionValue<T>) castOption.newValue(
                            filterChannelOptions((Map<ChannelOption<?>, Object>) value));
            optionValue = castOptionValue;
        }

        return optionValue;
    }

    private static Map<ChannelOption<?>, Object> filterChannelOptions(
            Map<ChannelOption<?>, Object> channelOptions) {

        channelOptions = Collections.unmodifiableMap(requireNonNull(channelOptions, "channelOptions"));

        for (ChannelOption<?> channelOption : PROHIBITED_SOCKET_OPTIONS) {
            if (channelOptions.containsKey(channelOption)) {
                throw new IllegalArgumentException("unallowed channelOption: " + channelOption);
            }
        }

        return channelOptions;
    }

    private ClientFactoryOptions(ClientFactoryOptionValue<?>... options) {
        super(ClientFactoryOptions::filterValue, options);
    }

    private ClientFactoryOptions(ClientFactoryOptions clientFactoryOptions,
                                 ClientFactoryOptionValue<?>... options) {

        super(ClientFactoryOptions::filterValue, clientFactoryOptions, options);
    }

    private ClientFactoryOptions(ClientFactoryOptions clientFactoryOptions,
                                 Iterable<ClientFactoryOptionValue<?>> options) {

        super(ClientFactoryOptions::filterValue, clientFactoryOptions, options);
    }

    private ClientFactoryOptions(ClientFactoryOptions clientFactoryOptions,
                                 ClientFactoryOptions options) {

        super(clientFactoryOptions, options);
    }

    /**
     * Converts this {@link ClientFactoryOptions} to a {@link Map}.
     */
    public Map<ClientFactoryOption<Object>, ClientFactoryOptionValue<Object>> asMap() {
        return asMap0();
    }

    /**
     * Returns the worker {@link EventLoopGroup}.
     */
    public EventLoopGroup workerGroup() {
        return get0(ClientFactoryOption.WORKER_GROUP).get();
    }

    /**
     * Returns the flag whether to shut down the worker {@link EventLoopGroup}
     * when the {@link ClientFactory} is closed.
     */
    public boolean shutdownWorkerGroupOnClose() {
        return get0(ClientFactoryOption.SHUTDOWN_WORKER_GROUP_ON_CLOSE).get();
    }

    /**
     * Returns the factory that creates an {@link EventLoopScheduler} which is responsible for assigning an
     * {@link EventLoop} to handle a connection to the specified {@link Endpoint}.
     */
    public Function<? super EventLoopGroup, ? extends EventLoopScheduler> eventLoopSchedulerFactory() {
        return get0(ClientFactoryOption.EVENT_LOOP_SCHEDULER_FACTORY).get();
    }

    /**
     * Returns the {@link ChannelOption}s of the sockets created by the {@link ClientFactory}.
     */
    public Map<ChannelOption<?>, Object> channelOptions() {
        return get0(ClientFactoryOption.CHANNEL_OPTIONS).get();
    }

    /**
     * Returns the {@link Consumer} which can arbitrarily configure the {@link SslContextBuilder} that will be
     * applied to the SSL session.
     *
     * @deprecated Use {@link #tlsCustomizer()}.
     */
    @Deprecated
    public Consumer<? super SslContextBuilder> sslContextCustomizer() {
        return tlsCustomizer();
    }

    /**
     * Returns the {@link Consumer} which can arbitrarily configure the {@link SslContextBuilder} that will be
     * applied to the SSL session.
     */
    public Consumer<? super SslContextBuilder> tlsCustomizer() {
        return get0(ClientFactoryOption.TLS_CUSTOMIZER).get();
    }

    /**
     * Returns the factory that creates an {@link AddressResolverGroup} which resolves remote addresses into
     * {@link InetSocketAddress}es.
     */
    public Function<? super EventLoopGroup,
            ? extends AddressResolverGroup<? extends InetSocketAddress>> addressResolverGroupFactory() {

        return get0(ClientFactoryOption.ADDRESS_RESOLVER_GROUP_FACTORY).get();
    }

    /**
     * Returns the HTTP/2 <a href="https://tools.ietf.org/html/rfc7540#section-6.9.2">initial connection
     * flow-control window size</a>.
     */
    public int http2InitialConnectionWindowSize() {
        return get0(ClientFactoryOption.HTTP2_INITIAL_CONNECTION_WINDOW_SIZE).get();
    }

    /**
     * Returns the <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_INITIAL_WINDOW_SIZE</a>
     * for HTTP/2 stream-level flow control.
     */
    public int http2InitialStreamWindowSize() {
        return get0(ClientFactoryOption.HTTP2_INITIAL_STREAM_WINDOW_SIZE).get();
    }

    /**
     * Returns the <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_MAX_FRAME_SIZE</a>
     * that indicates the size of the largest frame payload that this client is willing to receive.
     */
    public int http2MaxFrameSize() {
        return get0(ClientFactoryOption.HTTP2_MAX_FRAME_SIZE).get();
    }

    /**
     * Returns the HTTP/2 <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">
     * SETTINGS_MAX_HEADER_LIST_SIZE</a> that indicates the maximum size of header list
     * that the client is prepared to accept, in octets.
     */
    public long http2MaxHeaderListSize() {
        return get0(ClientFactoryOption.HTTP2_MAX_HEADER_LIST_SIZE).get();
    }

    /**
     * Returns the maximum length of an HTTP/1 response initial line.
     */
    public int http1MaxInitialLineLength() {
        return get0(ClientFactoryOption.HTTP1_MAX_INITIAL_LINE_LENGTH).get();
    }

    /**
     * Returns the maximum length of all headers in an HTTP/1 response.
     */
    public int http1MaxHeaderSize() {
        return get0(ClientFactoryOption.HTTP1_MAX_HEADER_SIZE).get();
    }

    /**
     * Returns the maximum length of each chunk in an HTTP/1 response content.
     */
    public int http1MaxChunkSize() {
        return get0(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE).get();
    }

    /**
     * Returns the idle timeout of a socket connection in milliseconds.
     */
    public long idleTimeoutMillis() {
        return get0(ClientFactoryOption.IDLE_TIMEOUT_MILLIS).get();
    }

    /**
     * Returns whether to send an HTTP/2 preface string instead of an HTTP/1 upgrade request to negotiate
     * the protocol version of a cleartext HTTP connection.
     */
    public boolean useHttp2Preface() {
        return get0(ClientFactoryOption.USE_HTTP2_PREFACE).get();
    }

    /**
     * Returns whether to use <a href="https://en.wikipedia.org/wiki/HTTP_pipelining">HTTP pipelining</a> for
     * HTTP/1 connections.
     */
    public boolean useHttp1Pipelining() {
        return get0(ClientFactoryOption.USE_HTTP1_PIPELINING).get();
    }

    /**
     * Returns the listener which is notified on a connection pool event.
     */
    public ConnectionPoolListener connectionPoolListener() {
        return get0(ClientFactoryOption.CONNECTION_POOL_LISTENER).get();
    }

    /**
     * Returns the {@link MeterRegistry} which collects various stats.
     */
    public MeterRegistry meterRegistry() {
        return get0(ClientFactoryOption.METER_REGISTRY).get();
    }
}

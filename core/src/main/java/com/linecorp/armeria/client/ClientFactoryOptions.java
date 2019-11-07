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

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.util.AbstractOptions;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.AddressResolverGroup;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;

public final class ClientFactoryOptions extends AbstractOptions {
    private static final EventLoopGroup DEFAULT_WORKER_GROUP = CommonPools.workerGroup();
    private static final EventLoopScheduler DEFAULT_EVENT_LOOP_SCHEDULER =
            new DefaultEventLoopScheduler(DEFAULT_WORKER_GROUP, 0, 0, Collections.emptyList());
    private static final Consumer<SslContextBuilder> DEFAULT_SSL_CONTEXT_CUSTOMIZER = b -> { /* no-op */ };
    private static final AddressResolverGroup<InetSocketAddress> DEFAULT_ADDRESS_RESOLVER_GROUP =
            new DnsResolverGroupBuilder().build(DEFAULT_WORKER_GROUP);
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
            ClientFactoryOption.EVENT_LOOP_SCHEDULER.newValue(DEFAULT_EVENT_LOOP_SCHEDULER),
            ClientFactoryOption.CHANNEL_OPTIONS.newValue(new Object2ObjectArrayMap<>()),
            ClientFactoryOption.SSL_CONTEXT_CUSTOMIZER.newValue(DEFAULT_SSL_CONTEXT_CUSTOMIZER),
            ClientFactoryOption.ADDRESS_RESOLVER_GROUP.newValue(DEFAULT_ADDRESS_RESOLVER_GROUP),
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

    public static final ClientFactoryOptions DEFAULT = new ClientFactoryOptions(DEFAULT_OPTIONS);

    /**
     * TBW.
     */
    public static ClientFactoryOptions of(ClientFactoryOptionValue<?>... options) {
        requireNonNull(options, "options");
        if (options.length == 0) {
            return DEFAULT;
        }
        return new ClientFactoryOptions(DEFAULT, options);
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

        requireNonNull(channelOptions, "channelOptions");

        for (ChannelOption<?> channelOption : PROHIBITED_SOCKET_OPTIONS) {
            if (channelOptions.containsKey(channelOption)) {
                throw new IllegalArgumentException("unallowed channelOption: " + channelOption);
            }
        }

        return channelOptions;
    }

    /**
     * TBW.
     */
    public Map<ClientFactoryOption<Object>, ClientFactoryOptionValue<Object>> asMap() {
        return asMap0();
    }

    /**
     * TBW.
     */
    public EventLoopGroup workerGroup() {
        return getOrElse0(ClientFactoryOption.WORKER_GROUP, CommonPools.workerGroup());
    }

    /**
     * TBW.
     */
    public boolean shutdownWorkerGroupOnClose() {
        return getOrElse0(ClientFactoryOption.SHUTDOWN_WORKER_GROUP_ON_CLOSE, false);
    }

    /**
     * TBW.
     */
    public EventLoopScheduler eventLoopScheduler() {
        return getOrElse0(ClientFactoryOption.EVENT_LOOP_SCHEDULER,
                          new DefaultEventLoopScheduler(workerGroup(), 0, 0, Collections.emptyList()));
    }

    /**
     * TBW.
     */
    public Map<ChannelOption<?>, Object> channelOptions() {
        return getOrElse0(ClientFactoryOption.CHANNEL_OPTIONS, new Object2ObjectArrayMap<>());
    }

    /**
     * TBW.
     */
    public Consumer<? super SslContextBuilder> sslContextCustomizer() {
        return getOrElse0(ClientFactoryOption.SSL_CONTEXT_CUSTOMIZER, DEFAULT_SSL_CONTEXT_CUSTOMIZER);
    }

    /**
     * TBW.
     */
    public AddressResolverGroup<InetSocketAddress> addressResolverGroup() {
        return getOrElse0(ClientFactoryOption.ADDRESS_RESOLVER_GROUP,
                          new DnsResolverGroupBuilder().build(workerGroup()));
    }

    /**
     * TBW.
     */
    public int http2InitialConnectionWindowSize() {
        return getOrElse0(ClientFactoryOption.HTTP2_INITIAL_CONNECTION_WINDOW_SIZE,
                          Flags.defaultHttp2InitialConnectionWindowSize());
    }

    /**
     * TBW.
     */
    public int http2InitialStreamWindowSize() {
        return getOrElse0(ClientFactoryOption.HTTP2_INITIAL_STREAM_WINDOW_SIZE,
                          Flags.defaultHttp2InitialStreamWindowSize());
    }

    /**
     * TBW.
     */
    public int http2MaxFrameSize() {
        return getOrElse0(ClientFactoryOption.HTTP2_MAX_FRAME_SIZE,
                          Flags.defaultHttp2MaxFrameSize());
    }

    /**
     * TBW.
     */
    public long http2MaxHeaderListSize() {
        return getOrElse0(ClientFactoryOption.HTTP2_MAX_HEADER_LIST_SIZE,
                          Flags.defaultHttp2MaxHeaderListSize());
    }

    /**
     * TBW.
     */
    public int http1MaxInitialLineLength() {
        return getOrElse0(ClientFactoryOption.HTTP1_MAX_INITIAL_LINE_LENGTH,
                          Flags.defaultHttp1MaxInitialLineLength());
    }

    /**
     * TBW.
     */
    public int http1MaxHeaderSize() {
        return getOrElse0(ClientFactoryOption.HTTP1_MAX_HEADER_SIZE, Flags.defaultHttp1MaxHeaderSize());
    }

    /**
     * TBW.
     */
    public int http1MaxChunkSize() {
        return getOrElse0(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE, Flags.defaultHttp1MaxChunkSize());
    }

    /**
     * TBW.
     */
    public long idleTimeoutMillis() {
        return getOrElse0(ClientFactoryOption.IDLE_TIMEOUT_MILLIS, Flags.defaultClientIdleTimeoutMillis());
    }

    /**
     * TBW.
     */
    public boolean useHttp2Preface() {
        return getOrElse0(ClientFactoryOption.USE_HTTP2_PREFACE, Flags.defaultUseHttp2Preface());
    }

    /**
     * TBW.
     */
    public boolean useHttp1Pipelining() {
        return getOrElse0(ClientFactoryOption.USE_HTTP1_PIPELINING, Flags.defaultUseHttp1Pipelining());
    }

    /**
     * TBW.
     */
    public ConnectionPoolListener connectionPoolListener() {
        return getOrElse0(ClientFactoryOption.CONNECTION_POOL_LISTENER, DEFAULT_CONNECTION_POOL_LISTENER);
    }

    /**
     * TBW.
     */
    public MeterRegistry meterRegistry() {
        return getOrElse0(ClientFactoryOption.METER_REGISTRY, Metrics.globalRegistry);
    }

    private ClientFactoryOptions(ClientFactoryOptionValue<?>... options) {
        super(ClientFactoryOptions::filterValue, options);
    }

    private ClientFactoryOptions(ClientFactoryOptions clientFactoryOptions,
                                 ClientFactoryOptionValue<?>... options) {

        super(ClientFactoryOptions::filterValue, clientFactoryOptions, options);
    }
}

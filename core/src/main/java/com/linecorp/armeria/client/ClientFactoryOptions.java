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

import java.util.Map;
import java.util.function.Consumer;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.util.AbstractOptions;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContextBuilder;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;

public final class ClientFactoryOptions extends AbstractOptions {
    private static final ConnectionPoolListener DEFAULT_CONNECTION_POOL_LISTENER =
            ConnectionPoolListener.noop();

    private static final Consumer<SslContextBuilder> DEFAULT_SSL_CONTEXT_CUSTOMIZER = b -> { /* no-op */ };

    private static final ClientFactoryOptionValue<?>[] DEFAULT_OPTIONS = {};

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

        return optionValue;
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

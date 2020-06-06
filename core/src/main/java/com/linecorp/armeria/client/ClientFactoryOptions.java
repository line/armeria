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
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.proxy.ProxyConfigSelector;
import com.linecorp.armeria.common.util.AbstractOptions;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.AddressResolverGroup;

/**
 * A set of {@link ClientFactoryOption}s and their respective values.
 */
public final class ClientFactoryOptions
        extends AbstractOptions<ClientFactoryOption<Object>, ClientFactoryOptionValue<Object>> {

    private static final ClientFactoryOptions EMPTY = new ClientFactoryOptions(ImmutableList.of());

    /**
     * Returns an empty singleton {@link ClientFactoryOptions}.
     */
    public static ClientFactoryOptions of() {
        return EMPTY;
    }

    /**
     * Returns the {@link ClientFactoryOptions} with the specified {@link ClientFactoryOptionValue}s.
     */
    public static ClientFactoryOptions of(ClientFactoryOptionValue<?>... values) {
        requireNonNull(values, "values");
        if (values.length == 0) {
            return EMPTY;
        }
        return of(Arrays.asList(values));
    }

    /**
     * Returns the {@link ClientFactoryOptions} with the specified {@link ClientFactoryOptionValue}s.
     */
    public static ClientFactoryOptions of(Iterable<? extends ClientFactoryOptionValue<?>> values) {
        requireNonNull(values, "values");
        if (values instanceof ClientFactoryOptions) {
            return (ClientFactoryOptions) values;
        }
        return new ClientFactoryOptions(values);
    }

    /**
     * Merges the specified {@link ClientFactoryOptions} and {@link ClientFactoryOptionValue}s.
     *
     * @return the merged {@link ClientFactoryOptions}
     */
    public static ClientFactoryOptions of(ClientFactoryOptions baseOptions,
                                          ClientFactoryOptionValue<?>... additionalValues) {
        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(additionalValues, "additionalValues");
        if (additionalValues.length == 0) {
            return baseOptions;
        }
        return new ClientFactoryOptions(baseOptions, Arrays.asList(additionalValues));
    }

    /**
     * Merges the specified {@link ClientFactoryOptions} and {@link ClientFactoryOptionValue}s.
     *
     * @return the merged {@link ClientFactoryOptions}
     */
    public static ClientFactoryOptions of(ClientFactoryOptions baseOptions,
                                          Iterable<? extends ClientFactoryOptionValue<?>> additionalValues) {
        requireNonNull(baseOptions, "baseOptions");
        requireNonNull(additionalValues, "additionalValues");
        return new ClientFactoryOptions(baseOptions, additionalValues);
    }

    private ClientFactoryOptions(Iterable<? extends ClientFactoryOptionValue<?>> values) {
        super(values);
    }

    private ClientFactoryOptions(ClientFactoryOptions baseOptions,
                                 Iterable<? extends ClientFactoryOptionValue<?>> additionalValues) {

        super(baseOptions, additionalValues);
    }

    /**
     * Returns the worker {@link EventLoopGroup}.
     */
    public EventLoopGroup workerGroup() {
        return get(ClientFactoryOption.WORKER_GROUP);
    }

    /**
     * Returns the flag whether to shut down the worker {@link EventLoopGroup}
     * when the {@link ClientFactory} is closed.
     */
    public boolean shutdownWorkerGroupOnClose() {
        return get(ClientFactoryOption.SHUTDOWN_WORKER_GROUP_ON_CLOSE);
    }

    /**
     * Returns the factory that creates an {@link EventLoopScheduler} which is responsible for assigning an
     * {@link EventLoop} to handle a connection to the specified {@link Endpoint}.
     */
    public Function<? super EventLoopGroup, ? extends EventLoopScheduler> eventLoopSchedulerFactory() {
        return get(ClientFactoryOption.EVENT_LOOP_SCHEDULER_FACTORY);
    }

    /**
     * Returns the {@link ChannelOption}s of the sockets created by the {@link ClientFactory}.
     */
    public Map<ChannelOption<?>, Object> channelOptions() {
        return get(ClientFactoryOption.CHANNEL_OPTIONS);
    }

    /**
     * Returns the {@link Consumer} which can arbitrarily configure the {@link SslContextBuilder} that will be
     * applied to the SSL session.
     */
    public Consumer<? super SslContextBuilder> tlsCustomizer() {
        return get(ClientFactoryOption.TLS_CUSTOMIZER);
    }

    /**
     * Returns the factory that creates an {@link AddressResolverGroup} which resolves remote addresses into
     * {@link InetSocketAddress}es.
     */
    public Function<? super EventLoopGroup,
            ? extends AddressResolverGroup<? extends InetSocketAddress>> addressResolverGroupFactory() {

        return get(ClientFactoryOption.ADDRESS_RESOLVER_GROUP_FACTORY);
    }

    /**
     * Returns the HTTP/2 <a href="https://tools.ietf.org/html/rfc7540#section-6.9.2">initial connection
     * flow-control window size</a>.
     */
    public int http2InitialConnectionWindowSize() {
        return get(ClientFactoryOption.HTTP2_INITIAL_CONNECTION_WINDOW_SIZE);
    }

    /**
     * Returns the <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_INITIAL_WINDOW_SIZE</a>
     * for HTTP/2 stream-level flow control.
     */
    public int http2InitialStreamWindowSize() {
        return get(ClientFactoryOption.HTTP2_INITIAL_STREAM_WINDOW_SIZE);
    }

    /**
     * Returns the <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_MAX_FRAME_SIZE</a>
     * that indicates the size of the largest frame payload that this client is willing to receive.
     */
    public int http2MaxFrameSize() {
        return get(ClientFactoryOption.HTTP2_MAX_FRAME_SIZE);
    }

    /**
     * Returns the HTTP/2 <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">
     * SETTINGS_MAX_HEADER_LIST_SIZE</a> that indicates the maximum size of header list
     * that the client is prepared to accept, in octets.
     */
    public long http2MaxHeaderListSize() {
        return get(ClientFactoryOption.HTTP2_MAX_HEADER_LIST_SIZE);
    }

    /**
     * Returns the maximum length of an HTTP/1 response initial line.
     */
    public int http1MaxInitialLineLength() {
        return get(ClientFactoryOption.HTTP1_MAX_INITIAL_LINE_LENGTH);
    }

    /**
     * Returns the maximum length of all headers in an HTTP/1 response.
     */
    public int http1MaxHeaderSize() {
        return get(ClientFactoryOption.HTTP1_MAX_HEADER_SIZE);
    }

    /**
     * Returns the maximum length of each chunk in an HTTP/1 response content.
     */
    public int http1MaxChunkSize() {
        return get(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE);
    }

    /**
     * Returns the idle timeout of a socket connection in milliseconds.
     */
    public long idleTimeoutMillis() {
        return get(ClientFactoryOption.IDLE_TIMEOUT_MILLIS);
    }

    /**
     * Returns the PING interval in milliseconds.
     * When neither read nor write was performed for the specified period of time,
     * a <a href="https://httpwg.org/specs/rfc7540.html#PING">PING</a> frame is sent for HTTP/2 or
     * an <a herf="https://tools.ietf.org/html/rfc7231#section-4.3.7">OPTIONS</a> request with an asterisk ("*")
     * is sent for HTTP/1.
     */
    public long pingIntervalMillis() {
        return get(ClientFactoryOption.PING_INTERVAL_MILLIS);
    }

    /**
     * Returns whether to send an HTTP/2 preface string instead of an HTTP/1 upgrade request to negotiate
     * the protocol version of a cleartext HTTP connection.
     */
    public boolean useHttp2Preface() {
        return get(ClientFactoryOption.USE_HTTP2_PREFACE);
    }

    /**
     * Returns whether to use <a href="https://en.wikipedia.org/wiki/HTTP_pipelining">HTTP pipelining</a> for
     * HTTP/1 connections.
     */
    public boolean useHttp1Pipelining() {
        return get(ClientFactoryOption.USE_HTTP1_PIPELINING);
    }

    /**
     * Returns the listener which is notified on a connection pool event.
     */
    public ConnectionPoolListener connectionPoolListener() {
        return get(ClientFactoryOption.CONNECTION_POOL_LISTENER);
    }

    /**
     * Returns the {@link MeterRegistry} which collects various stats.
     */
    public MeterRegistry meterRegistry() {
        return get(ClientFactoryOption.METER_REGISTRY);
    }

    /**
     * The {@link ProxyConfigSelector} which determines the proxy configuration.
     */
    public ProxyConfigSelector proxyConfigSelector() {
        return get(ClientFactoryOption.PROXY_CONFIG_SELECTOR);
    }
}

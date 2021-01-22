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
import static com.linecorp.armeria.client.ClientFactoryBuilder.MIN_MAX_CONNECTION_AGE_MILLIS;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.proxy.ProxyConfig;
import com.linecorp.armeria.client.proxy.ProxyConfigSelector;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Http1HeaderNaming;
import com.linecorp.armeria.common.util.AbstractOptions;
import com.linecorp.armeria.internal.common.util.ChannelUtil;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.AsciiString;

/**
 * A set of {@link ClientFactoryOption}s and their respective values.
 */
public final class ClientFactoryOptions
        extends AbstractOptions<ClientFactoryOption<Object>, ClientFactoryOptionValue<Object>> {

    /**
     * The worker {@link EventLoopGroup}.
     */
    public static final ClientFactoryOption<EventLoopGroup> WORKER_GROUP =
            ClientFactoryOption.define("WORKER_GROUP", CommonPools.workerGroup());

    /**
     * Whether to shut down the worker {@link EventLoopGroup} when the {@link ClientFactory} is closed.
     */
    public static final ClientFactoryOption<Boolean> SHUTDOWN_WORKER_GROUP_ON_CLOSE =
            ClientFactoryOption.define("SHUTDOWN_WORKER_GROUP_ON_CLOSE", false);

    /**
     * The factory that creates an {@link EventLoopScheduler} which is responsible for assigning an
     * {@link EventLoop} to handle a connection to the specified {@link Endpoint}.
     */
    public static final ClientFactoryOption<Function<? super EventLoopGroup, ? extends EventLoopScheduler>>
            EVENT_LOOP_SCHEDULER_FACTORY = ClientFactoryOption.define(
            "EVENT_LOOP_SCHEDULER_FACTORY",
            eventLoopGroup -> new DefaultEventLoopScheduler(eventLoopGroup, 0, 0, ImmutableList.of()));

    /**
     * The {@link Consumer} which can arbitrarily configure the {@link SslContextBuilder} that will be
     * applied to the SSL session.
     */
    public static final ClientFactoryOption<Consumer<? super SslContextBuilder>> TLS_CUSTOMIZER =
            ClientFactoryOption.define("TLS_CUSTOMIZER", b -> { /* no-op */ });

    /**
     * Whether to allow the bad cipher suites listed in
     * <a href="https://tools.ietf.org/html/rfc7540#appendix-A">RFC7540</a> for TLS handshake.
     *
     * <p>Note that enabling this option increases the security risk of your connection.
     * Use it only when you must communicate with a legacy system that does not support
     * secure cipher suites.
     * See <a href="https://tools.ietf.org/html/rfc7540#section-9.2.2">Section 9.2.2, RFC7540</a> for
     * more information.
     */
    public static final ClientFactoryOption<Boolean> TLS_ALLOW_UNSAFE_CIPHERS =
            ClientFactoryOption.define("tlsAllowUnsafeCiphers", Flags.tlsAllowUnsafeCiphers());

    /**
     * The factory that creates an {@link AddressResolverGroup} which resolves remote addresses into
     * {@link InetSocketAddress}es.
     */
    public static final ClientFactoryOption<Function<? super EventLoopGroup,
            ? extends AddressResolverGroup<? extends InetSocketAddress>>> ADDRESS_RESOLVER_GROUP_FACTORY =
            ClientFactoryOption.define("ADDRESS_RESOLVER_GROUP_FACTORY",
                                       eventLoopGroup -> new DnsResolverGroupBuilder().build(eventLoopGroup));

    /**
     * The HTTP/2 <a href="https://tools.ietf.org/html/rfc7540#section-6.9.2">initial connection flow-control
     * window size</a>.
     */
    public static final ClientFactoryOption<Integer> HTTP2_INITIAL_CONNECTION_WINDOW_SIZE =
            ClientFactoryOption.define("HTTP2_INITIAL_CONNECTION_WINDOW_SIZE",
                                       Flags.defaultHttp2InitialConnectionWindowSize());

    /**
     * The <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_INITIAL_WINDOW_SIZE</a>
     * for HTTP/2 stream-level flow control.
     */
    public static final ClientFactoryOption<Integer> HTTP2_INITIAL_STREAM_WINDOW_SIZE =
            ClientFactoryOption.define("HTTP2_INITIAL_STREAM_WINDOW_SIZE",
                                       Flags.defaultHttp2InitialStreamWindowSize());

    /**
     * The <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_MAX_FRAME_SIZE</a>
     * that indicates the size of the largest frame payload that this client is willing to receive.
     */
    public static final ClientFactoryOption<Integer> HTTP2_MAX_FRAME_SIZE =
            ClientFactoryOption.define("HTTP2_MAX_FRAME_SIZE", Flags.defaultHttp2MaxFrameSize());

    /**
     * The HTTP/2 <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_MAX_HEADER_LIST_SIZE</a>
     * that indicates the maximum size of header list that the client is prepared to accept, in octets.
     */
    public static final ClientFactoryOption<Long> HTTP2_MAX_HEADER_LIST_SIZE =
            ClientFactoryOption.define("HTTP2_MAX_HEADER_LIST_SIZE", Flags.defaultHttp2MaxHeaderListSize());

    /**
     * The maximum length of an HTTP/1 response initial line.
     */
    public static final ClientFactoryOption<Integer> HTTP1_MAX_INITIAL_LINE_LENGTH =
            ClientFactoryOption.define("HTTP1_MAX_INITIAL_LINE_LENGTH",
                                       Flags.defaultHttp1MaxInitialLineLength());

    /**
     * The maximum length of all headers in an HTTP/1 response.
     */
    public static final ClientFactoryOption<Integer> HTTP1_MAX_HEADER_SIZE =
            ClientFactoryOption.define("HTTP1_MAX_HEADER_SIZE", Flags.defaultHttp1MaxHeaderSize());

    /**
     * The maximum length of each chunk in an HTTP/1 response content.
     */
    public static final ClientFactoryOption<Integer> HTTP1_MAX_CHUNK_SIZE =
            ClientFactoryOption.define("HTTP1_MAX_CHUNK_SIZE", Flags.defaultHttp1MaxChunkSize());

    /**
     * The idle timeout of a socket connection in milliseconds.
     */
    public static final ClientFactoryOption<Long> IDLE_TIMEOUT_MILLIS =
            ClientFactoryOption.define("IDLE_TIMEOUT_MILLIS", Flags.defaultClientIdleTimeoutMillis());

    /**
     * The PING interval in milliseconds.
     * When neither read nor write was performed for the specified period of time,
     * a <a href="https://httpwg.org/specs/rfc7540.html#PING">PING</a> frame is sent for HTTP/2 or
     * an <a href="https://tools.ietf.org/html/rfc7231#section-4.3.7">OPTIONS</a> request with an asterisk ("*")
     * is sent for HTTP/1.
     */
    public static final ClientFactoryOption<Long> PING_INTERVAL_MILLIS =
            ClientFactoryOption.define("PING_INTERVAL_MILLIS", Flags.defaultPingIntervalMillis());

    /**
     * The client-side max age of a connection for keep-alive in milliseconds.
     * If the value is greater than {@code 0}, a connection is disconnected after the specified
     * amount of time since the connection was established.
     * This option is disabled by default, which means unlimited.
     */
    public static final ClientFactoryOption<Long> MAX_CONNECTION_AGE_MILLIS =
            ClientFactoryOption.define("MAX_CONNECTION_AGE_MILLIS", clampedDefaultMaxClientConnectionAge());

    private static long clampedDefaultMaxClientConnectionAge() {
        final long connectionAgeMillis = Flags.defaultMaxClientConnectionAgeMillis();
        if (connectionAgeMillis > 0 && connectionAgeMillis < MIN_MAX_CONNECTION_AGE_MILLIS) {
            return MIN_MAX_CONNECTION_AGE_MILLIS;
        }
        return connectionAgeMillis;
    }

    /**
     * The client-side maximum allowed number of requests that can be sent through one connection.
     * This option is disabled by default, which means unlimited.
     */
    public static final ClientFactoryOption<Integer> MAX_NUM_REQUESTS_PER_CONNECTION =
            ClientFactoryOption.define("MAX_NUM_REQUESTS_PER_CONNECTION",
                                       Flags.defaultMaxClientNumRequestsPerConnection());

    /**
     * Whether to send an HTTP/2 preface string instead of an HTTP/1 upgrade request to negotiate
     * the protocol version of a cleartext HTTP connection.
     */
    public static final ClientFactoryOption<Boolean> USE_HTTP2_PREFACE =
            ClientFactoryOption.define("USE_HTTP2_PREFACE", Flags.defaultUseHttp2Preface());

    /**
     * Whether to use <a href="https://en.wikipedia.org/wiki/HTTP_pipelining">HTTP pipelining</a> for
     * HTTP/1 connections.
     */
    public static final ClientFactoryOption<Boolean> USE_HTTP1_PIPELINING =
            ClientFactoryOption.define("USE_HTTP1_PIPELINING", Flags.defaultUseHttp1Pipelining());

    /**
     * The listener which is notified on a connection pool event.
     */
    public static final ClientFactoryOption<ConnectionPoolListener> CONNECTION_POOL_LISTENER =
            ClientFactoryOption.define("CONNECTION_POOL_LISTENER", ConnectionPoolListener.noop());

    /**
     * The {@link MeterRegistry} which collects various stats.
     */
    public static final ClientFactoryOption<MeterRegistry> METER_REGISTRY =
            ClientFactoryOption.define("METER_REGISTRY", Metrics.globalRegistry);

    /**
     * The {@link ProxyConfigSelector} which determines the {@link ProxyConfig} to be used.
     */
    public static final ClientFactoryOption<ProxyConfigSelector> PROXY_CONFIG_SELECTOR =
            ClientFactoryOption.define("PROXY_CONFIG_SELECTOR", ProxyConfigSelector.of(ProxyConfig.direct()));

    /**
     * The {@link Http1HeaderNaming} which converts a lower-cased HTTP/2 header name into
     * another HTTP/1 header name.
     */
    public static final ClientFactoryOption<Http1HeaderNaming> HTTP1_HEADER_NAMING =
            ClientFactoryOption.define("HTTP1_HEADER_NAMING", AsciiString::toString);

    /**
     * The {@link ChannelOption}s of the sockets created by the {@link ClientFactory}.
     */
    public static final ClientFactoryOption<Map<ChannelOption<?>, Object>> CHANNEL_OPTIONS =
            ClientFactoryOption.define("CHANNEL_OPTIONS", ImmutableMap.of(), newOptions -> {
                for (ChannelOption<?> channelOption : ChannelUtil.prohibitedOptions()) {
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
        return get(WORKER_GROUP);
    }

    /**
     * Returns the flag whether to shut down the worker {@link EventLoopGroup}
     * when the {@link ClientFactory} is closed.
     */
    public boolean shutdownWorkerGroupOnClose() {
        return get(SHUTDOWN_WORKER_GROUP_ON_CLOSE);
    }

    /**
     * Returns the factory that creates an {@link EventLoopScheduler} which is responsible for assigning an
     * {@link EventLoop} to handle a connection to the specified {@link Endpoint}.
     */
    public Function<? super EventLoopGroup, ? extends EventLoopScheduler> eventLoopSchedulerFactory() {
        return get(EVENT_LOOP_SCHEDULER_FACTORY);
    }

    /**
     * Returns the {@link ChannelOption}s of the sockets created by the {@link ClientFactory}.
     */
    public Map<ChannelOption<?>, Object> channelOptions() {
        return get(CHANNEL_OPTIONS);
    }

    /**
     * Returns the {@link Consumer} which can arbitrarily configure the {@link SslContextBuilder} that will be
     * applied to the SSL session.
     */
    public Consumer<? super SslContextBuilder> tlsCustomizer() {
        return get(TLS_CUSTOMIZER);
    }

    /**
     * Returns the factory that creates an {@link AddressResolverGroup} which resolves remote addresses into
     * {@link InetSocketAddress}es.
     */
    public Function<? super EventLoopGroup,
            ? extends AddressResolverGroup<? extends InetSocketAddress>> addressResolverGroupFactory() {

        return get(ADDRESS_RESOLVER_GROUP_FACTORY);
    }

    /**
     * Returns the HTTP/2 <a href="https://tools.ietf.org/html/rfc7540#section-6.9.2">initial connection
     * flow-control window size</a>.
     */
    public int http2InitialConnectionWindowSize() {
        return get(HTTP2_INITIAL_CONNECTION_WINDOW_SIZE);
    }

    /**
     * Returns the <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_INITIAL_WINDOW_SIZE</a>
     * for HTTP/2 stream-level flow control.
     */
    public int http2InitialStreamWindowSize() {
        return get(HTTP2_INITIAL_STREAM_WINDOW_SIZE);
    }

    /**
     * Returns the <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_MAX_FRAME_SIZE</a>
     * that indicates the size of the largest frame payload that this client is willing to receive.
     */
    public int http2MaxFrameSize() {
        return get(HTTP2_MAX_FRAME_SIZE);
    }

    /**
     * Returns the HTTP/2 <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">
     * SETTINGS_MAX_HEADER_LIST_SIZE</a> that indicates the maximum size of header list
     * that the client is prepared to accept, in octets.
     */
    public long http2MaxHeaderListSize() {
        return get(HTTP2_MAX_HEADER_LIST_SIZE);
    }

    /**
     * Returns the maximum length of an HTTP/1 response initial line.
     */
    public int http1MaxInitialLineLength() {
        return get(HTTP1_MAX_INITIAL_LINE_LENGTH);
    }

    /**
     * Returns the maximum length of all headers in an HTTP/1 response.
     */
    public int http1MaxHeaderSize() {
        return get(HTTP1_MAX_HEADER_SIZE);
    }

    /**
     * Returns the maximum length of each chunk in an HTTP/1 response content.
     */
    public int http1MaxChunkSize() {
        return get(HTTP1_MAX_CHUNK_SIZE);
    }

    /**
     * Returns the idle timeout of a socket connection in milliseconds.
     */
    public long idleTimeoutMillis() {
        return get(IDLE_TIMEOUT_MILLIS);
    }

    /**
     * Returns the PING interval in milliseconds.
     * When neither read nor write was performed for the specified period of time,
     * a <a href="https://httpwg.org/specs/rfc7540.html#PING">PING</a> frame is sent for HTTP/2 or
     * an <a href="https://tools.ietf.org/html/rfc7231#section-4.3.7">OPTIONS</a> request with an asterisk ("*")
     * is sent for HTTP/1.
     */
    public long pingIntervalMillis() {
        return get(PING_INTERVAL_MILLIS);
    }

    /**
     * Returns the client-side max age of a connection for keep-alive in milliseconds.
     * If the value is greater than {@code 0}, a connection is disconnected after the specified
     * amount of the time since the connection was established.
     */
    public long maxConnectionAgeMillis() {
        return get(MAX_CONNECTION_AGE_MILLIS);
    }

    /**
     * Returns the client-side maximum allowed number of requests that can be sent through one connection.
     */
    public int maxNumRequestsPerConnection() {
        return get(MAX_NUM_REQUESTS_PER_CONNECTION);
    }

    /**
     * Returns whether to send an HTTP/2 preface string instead of an HTTP/1 upgrade request to negotiate
     * the protocol version of a cleartext HTTP connection.
     */
    public boolean useHttp2Preface() {
        return get(USE_HTTP2_PREFACE);
    }

    /**
     * Returns whether to use <a href="https://en.wikipedia.org/wiki/HTTP_pipelining">HTTP pipelining</a> for
     * HTTP/1 connections.
     */
    public boolean useHttp1Pipelining() {
        return get(USE_HTTP1_PIPELINING);
    }

    /**
     * Returns the listener which is notified on a connection pool event.
     */
    public ConnectionPoolListener connectionPoolListener() {
        return get(CONNECTION_POOL_LISTENER);
    }

    /**
     * Returns the {@link MeterRegistry} which collects various stats.
     */
    public MeterRegistry meterRegistry() {
        return get(METER_REGISTRY);
    }

    /**
     * The {@link ProxyConfigSelector} which determines the {@link ProxyConfig} to be used.
     */
    public ProxyConfigSelector proxyConfigSelector() {
        return get(PROXY_CONFIG_SELECTOR);
    }

    /**
     * Returns the {@link Http1HeaderNaming} which converts a lower-cased HTTP/2 header name into
     * another header name. This is useful when communicating with a legacy system that only supports
     * case sensitive HTTP/1 headers.
     */
    public Http1HeaderNaming http1HeaderNaming() {
        return get(HTTP1_HEADER_NAMING);
    }

    /**
     * Returns whether to allow the bad cipher suites listed in
     * <a href="https://tools.ietf.org/html/rfc7540#appendix-A">RFC7540</a> for TLS handshake.
     *
     * <p>Note that enabling this option increases the security risk of your connection.
     * Use it only when you must communicate with a legacy system that does not support
     * secure cipher suites.
     * See <a href="https://tools.ietf.org/html/rfc7540#section-9.2.2">Section 9.2.2, RFC7540</a> for
     * more information.
     */
    public boolean tlsAllowUnsafeCiphers() {
        return get(TLS_ALLOW_UNSAFE_CIPHERS);
    }
}

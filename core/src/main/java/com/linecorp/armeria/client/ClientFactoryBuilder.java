/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.MAX_FRAME_SIZE_LOWER_BOUND;
import static io.netty.handler.codec.http2.Http2CodecUtil.MAX_FRAME_SIZE_UPPER_BOUND;
import static io.netty.handler.codec.http2.Http2CodecUtil.MAX_INITIAL_WINDOW_SIZE;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Request;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;

/**
 * Builds a new {@link ClientFactory}.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * final ClientFactory factory = new ClientFactoryBuilder();
 *     // Set the connection timeout to 5 seconds.
 *     .connectTimeoutMillis(5000)
 *     // Set the socket send buffer to 1 MiB.
 *     .socketOption(ChannelOption.SO_SNDBUF, 1048576)
 *     // Disable certificate verification; never do this in production!
 *     .sslContextCustomizer(b -> b.trustManager(InsecureTrustManagerFactory.INSTANCE))
 *     .build();
 * }</pre>
 */
public final class ClientFactoryBuilder {

    private static final ConnectionPoolListener DEFAULT_CONNECTION_POOL_LISTENER =
            ConnectionPoolListener.noop();

    private static final Consumer<SslContextBuilder> DEFAULT_SSL_CONTEXT_CUSTOMIZER = b -> { /* no-op */ };

    // Do not accept 1) the options that may break Armeria and 2) the deprecated options.
    @SuppressWarnings("deprecation")
    private static final Set<ChannelOption<?>> PROHIBITED_SOCKET_OPTIONS = ImmutableSet.of(
            ChannelOption.ALLOW_HALF_CLOSURE, ChannelOption.AUTO_READ,
            ChannelOption.AUTO_CLOSE, ChannelOption.MAX_MESSAGES_PER_READ,
            ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, ChannelOption.WRITE_BUFFER_LOW_WATER_MARK,
            EpollChannelOption.EPOLL_MODE);

    // Netty-related properties:
    private EventLoopGroup workerGroup = CommonPools.workerGroup();
    private boolean shutdownWorkerGroupOnClose;
    private final Map<ChannelOption<?>, Object> channelOptions = new Object2ObjectArrayMap<>();
    private Consumer<? super SslContextBuilder> sslContextCustomizer = DEFAULT_SSL_CONTEXT_CUSTOMIZER;
    @Nullable
    private Function<? super EventLoopGroup,
            ? extends AddressResolverGroup<? extends InetSocketAddress>> addressResolverGroupFactory;
    @Nullable
    private List<Consumer<? super DnsNameResolverBuilder>> domainNameResolverCustomizers;
    private int http2InitialConnectionWindowSize = Flags.defaultHttp2InitialConnectionWindowSize();
    private int http2InitialStreamWindowSize = Flags.defaultHttp2InitialStreamWindowSize();
    private int http2MaxFrameSize = Flags.defaultHttp2MaxFrameSize();
    private long http2MaxHeaderListSize = Flags.defaultHttp2MaxHeaderListSize();
    private int http1MaxInitialLineLength = Flags.defaultHttp1MaxInitialLineLength();
    private int http1MaxHeaderSize = Flags.defaultHttp1MaxHeaderSize();
    private int http1MaxChunkSize = Flags.defaultHttp1MaxChunkSize();

    // Armeria-related properties:
    private long idleTimeoutMillis = Flags.defaultClientIdleTimeoutMillis();
    private boolean useHttp2Preface = Flags.defaultUseHttp2Preface();
    private boolean useHttp1Pipelining = Flags.defaultUseHttp1Pipelining();
    private ConnectionPoolListener connectionPoolListener = DEFAULT_CONNECTION_POOL_LISTENER;
    private MeterRegistry meterRegistry = Metrics.globalRegistry;

    /**
     * Creates a new instance.
     */
    public ClientFactoryBuilder() {
        connectTimeoutMillis(Flags.defaultConnectTimeoutMillis());
        channelOption(ChannelOption.SO_KEEPALIVE, true);
    }

    /**
     * Sets the worker {@link EventLoopGroup} which is responsible for performing socket I/O and running
     * {@link Client#execute(ClientRequestContext, Request)}.
     * If not set, {@linkplain CommonPools#workerGroup() the common worker group} is used.
     *
     * @param shutdownOnClose whether to shut down the worker {@link EventLoopGroup}
     *                        when the {@link ClientFactory} is closed
     */
    public ClientFactoryBuilder workerGroup(EventLoopGroup workerGroup, boolean shutdownOnClose) {
        this.workerGroup = requireNonNull(workerGroup, "workerGroup");
        shutdownWorkerGroupOnClose = shutdownOnClose;
        return this;
    }

    /**
     * Sets the timeout of a socket connection attempt.
     */
    public ClientFactoryBuilder connectTimeout(Duration connectTimeout) {
        requireNonNull(connectTimeout, "connectTimeout");
        checkArgument(!connectTimeout.isZero() && !connectTimeout.isNegative(),
                      "connectTimeout: %s (expected: > 0)", connectTimeout);
        return connectTimeoutMillis(connectTimeout.toMillis());
    }

    /**
     * Sets the timeout of a socket connection attempt in milliseconds.
     */
    public ClientFactoryBuilder connectTimeoutMillis(long connectTimeoutMillis) {
        checkArgument(connectTimeoutMillis > 0,
                      "connectTimeoutMillis: %s (expected: > 0)", connectTimeoutMillis);
        return channelOption(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                             ConvertUtils.safeLongToInt(connectTimeoutMillis));
    }

    /**
     * Sets the options of sockets created by the {@link ClientFactory}.
     *
     * @deprecated Use {@link #channelOption(ChannelOption, Object)}.
     */
    @Deprecated
    public <T> ClientFactoryBuilder socketOption(ChannelOption<T> option, T value) {
        return channelOption(option, value);
    }

    /**
     * Sets the options of sockets created by the {@link ClientFactory}.
     */
    public <T> ClientFactoryBuilder channelOption(ChannelOption<T> option, T value) {
        requireNonNull(option, "option");
        checkArgument(!PROHIBITED_SOCKET_OPTIONS.contains(option),
                      "prohibited socket option: %s", option);

        channelOptions.put(option, requireNonNull(value, "value"));
        return this;
    }

    /**
     * Sets the {@link Consumer} which can arbitrarily configure the {@link SslContextBuilder} that will be
     * applied to the SSL session. For example, use {@link SslContextBuilder#trustManager} to configure a
     * custom server CA or {@link SslContextBuilder#keyManager} to configure a client certificate for SSL
     * authorization.
     */
    public ClientFactoryBuilder sslContextCustomizer(Consumer<? super SslContextBuilder> sslContextCustomizer) {
        this.sslContextCustomizer = requireNonNull(sslContextCustomizer, "sslContextCustomizer");
        return this;
    }

    /**
     * Sets the factory that creates a {@link AddressResolverGroup} which resolves remote addresses into
     * {@link InetSocketAddress}es.
     *
     * @throws IllegalStateException if {@link #domainNameResolverCustomizer(Consumer)} was called already.
     */
    public ClientFactoryBuilder addressResolverGroupFactory(
            Function<? super EventLoopGroup,
                     ? extends AddressResolverGroup<? extends InetSocketAddress>> addressResolverGroupFactory) {
        requireNonNull(addressResolverGroupFactory, "addressResolverGroupFactory");
        checkState(domainNameResolverCustomizers == null,
                   "addressResolverGroupFactory() and domainNameResolverCustomizer() are mutually exclusive.");
        this.addressResolverGroupFactory = addressResolverGroupFactory;
        return this;
    }

    /**
     * Adds the specified {@link Consumer} which customizes the given {@link DnsNameResolverBuilder}.
     * This method is useful when you want to change the behavior of the default domain name resolver, such as
     * changing the DNS server list.
     *
     * @throws IllegalStateException if {@link #addressResolverGroupFactory(Function)} was called already.
     */
    public ClientFactoryBuilder domainNameResolverCustomizer(
            Consumer<? super DnsNameResolverBuilder> domainNameResolverCustomizer) {
        requireNonNull(domainNameResolverCustomizer, "domainNameResolverCustomizer");
        checkState(addressResolverGroupFactory == null,
                   "addressResolverGroupFactory() and domainNameResolverCustomizer() are mutually exclusive.");
        if (domainNameResolverCustomizers == null) {
            domainNameResolverCustomizers = new ArrayList<>();
        }
        domainNameResolverCustomizers.add(domainNameResolverCustomizer);
        return this;
    }

    /**
     * Sets the <a href="https://tools.ietf.org/html/rfc7540#section-6.9.2">initial connection flow-control
     * window size</a>. The HTTP/2 connection is first established with
     * {@value Http2CodecUtil#DEFAULT_WINDOW_SIZE} bytes of connection flow-control window size,
     * and it is changed if and only if {@code http2InitialConnectionWindowSize} is set.
     * Note that this setting affects the connection-level window size, not the window size of streams.
     *
     * @see #http2InitialStreamWindowSize(int)
     */
    public ClientFactoryBuilder http2InitialConnectionWindowSize(int http2InitialConnectionWindowSize) {
        checkArgument(http2InitialConnectionWindowSize >= DEFAULT_WINDOW_SIZE,
                      "http2InitialConnectionWindowSize: %s (expected: >= %s and <= %s)",
                      http2InitialConnectionWindowSize, DEFAULT_WINDOW_SIZE, MAX_INITIAL_WINDOW_SIZE);
        this.http2InitialConnectionWindowSize = http2InitialConnectionWindowSize;
        return this;
    }

    /**
     * Sets the <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_INITIAL_WINDOW_SIZE</a>
     * for HTTP/2 stream-level flow control. Note that this setting affects the window size of all streams,
     * not the connection-level window size.
     *
     * @see #http2InitialConnectionWindowSize(int)
     */
    public ClientFactoryBuilder http2InitialStreamWindowSize(int http2InitialStreamWindowSize) {
        checkArgument(http2InitialStreamWindowSize > 0,
                      "http2InitialStreamWindowSize: %s (expected: > 0 and <= %s)",
                      http2InitialStreamWindowSize, MAX_INITIAL_WINDOW_SIZE);
        this.http2InitialStreamWindowSize = http2InitialStreamWindowSize;
        return this;
    }

    /**
     * Sets the <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_MAX_FRAME_SIZE</a>
     * that indicates the size of the largest frame payload that this client is willing to receive.
     */
    public ClientFactoryBuilder http2MaxFrameSize(int http2MaxFrameSize) {
        checkArgument(http2MaxFrameSize >= MAX_FRAME_SIZE_LOWER_BOUND &&
                      http2MaxFrameSize <= MAX_FRAME_SIZE_UPPER_BOUND,
                      "http2MaxFramSize: %s (expected: >= %s and <= %s)",
                      http2MaxFrameSize, MAX_FRAME_SIZE_LOWER_BOUND, MAX_FRAME_SIZE_UPPER_BOUND);
        this.http2MaxFrameSize = http2MaxFrameSize;
        return this;
    }

    /**
     * Sets the <a href="https://tools.ietf.org/html/rfc7540#section-6.5.2">SETTINGS_MAX_HEADER_LIST_SIZE</a>
     * that indicates the maximum size of header list that the client is prepared to accept, in octets.
     */
    public ClientFactoryBuilder http2MaxHeaderListSize(long http2MaxHeaderListSize) {
        checkArgument(http2MaxHeaderListSize > 0 &&
                      http2MaxHeaderListSize <= 0xFFFFFFFFL,
                      "http2MaxHeaderListSize: %s (expected: a positive 32-bit unsigned integer)",
                      http2MaxHeaderListSize);
        this.http2MaxHeaderListSize = http2MaxHeaderListSize;
        return this;
    }

    /**
     * Sets the maximum length of an HTTP/1 response initial line.
     */
    public ClientFactoryBuilder http1MaxInitialLineLength(int http1MaxInitialLineLength) {
        checkArgument(http1MaxInitialLineLength >= 0,
                      "http1MaxInitialLineLength: %s (expected: >= 0)",
                      http1MaxInitialLineLength);
        this.http1MaxInitialLineLength = http1MaxInitialLineLength;
        return this;
    }

    /**
     * Sets the maximum length of all headers in an HTTP/1 response.
     */
    public ClientFactoryBuilder http1MaxHeaderSize(int http1MaxHeaderSize) {
        checkArgument(http1MaxHeaderSize >= 0,
                      "http1MaxHeaderSize: %s (expected: >= 0)",
                      http1MaxHeaderSize);
        this.http1MaxHeaderSize = http1MaxHeaderSize;
        return this;
    }

    /**
     * Sets the maximum length of each chunk in an HTTP/1 response content.
     * The content or a chunk longer than this value will be split into smaller chunks
     * so that their lengths never exceed it.
     */
    public ClientFactoryBuilder http1MaxChunkSize(int http1MaxChunkSize) {
        checkArgument(http1MaxChunkSize >= 0,
                      "http1MaxChunkSize: %s (expected: >= 0)",
                      http1MaxChunkSize);
        this.http1MaxChunkSize = http1MaxChunkSize;
        return this;
    }

    /**
     * Sets the idle timeout of a socket connection. The connection is closed if there is no request in
     * progress for this amount of time.
     */
    public ClientFactoryBuilder idleTimeout(Duration idleTimeout) {
        requireNonNull(idleTimeout, "idleTimeout");
        checkArgument(!idleTimeout.isNegative(), "idleTimeout: %s (expected: >= 0)", idleTimeout);
        return idleTimeoutMillis(idleTimeout.toMillis());
    }

    /**
     * Sets the idle timeout of a socket connection in milliseconds. The connection is closed if there is no
     * request in progress for this amount of time.
     */
    public ClientFactoryBuilder idleTimeoutMillis(long idleTimeoutMillis) {
        checkArgument(idleTimeoutMillis >= 0, "idleTimeoutMillis: %s (expected: >= 0)", idleTimeoutMillis);
        this.idleTimeoutMillis = idleTimeoutMillis;
        return this;
    }

    /**
     * Sets whether to send an HTTP/2 preface string instead of an HTTP/1 upgrade request to negotiate
     * the protocol version of a cleartext HTTP connection.
     */
    public ClientFactoryBuilder useHttp2Preface(boolean useHttp2Preface) {
        this.useHttp2Preface = useHttp2Preface;
        return this;
    }

    /**
     * Sets whether to use <a href="https://en.wikipedia.org/wiki/HTTP_pipelining">HTTP pipelining</a> for
     * HTTP/1 connections. This does not affect HTTP/2 connections. This option is disabled by default.
     */
    public ClientFactoryBuilder useHttp1Pipelining(boolean useHttp1Pipelining) {
        this.useHttp1Pipelining = useHttp1Pipelining;
        return this;
    }

    /**
     * Sets the listener which is notified on a connection pool event.
     */
    public ClientFactoryBuilder connectionPoolListener(
            ConnectionPoolListener connectionPoolListener) {
        this.connectionPoolListener = requireNonNull(connectionPoolListener, "connectionPoolListener");
        return this;
    }

    /**
     * Sets the {@link MeterRegistry} which collects various stats.
     */
    public ClientFactoryBuilder meterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        return this;
    }

    /**
     * Returns a newly-created {@link ClientFactory} based on the properties of this builder.
     */
    public ClientFactory build() {
        final Function<? super EventLoopGroup,
                       ? extends AddressResolverGroup<? extends InetSocketAddress>> addressResolverGroupFactory;
        if (this.addressResolverGroupFactory != null) {
            addressResolverGroupFactory = this.addressResolverGroupFactory;
        } else {
            addressResolverGroupFactory = new DefaultAddressResolverGroupFactory(
                    firstNonNull(domainNameResolverCustomizers, ImmutableList.of()));
        }

        return new DefaultClientFactory(new HttpClientFactory(
                workerGroup, shutdownWorkerGroupOnClose, channelOptions, sslContextCustomizer,
                addressResolverGroupFactory, http2InitialConnectionWindowSize, http2InitialStreamWindowSize,
                http2MaxFrameSize, http2MaxHeaderListSize, http1MaxInitialLineLength, http1MaxHeaderSize,
                http1MaxChunkSize, idleTimeoutMillis, useHttp2Preface,
                useHttp1Pipelining, connectionPoolListener, meterRegistry));
    }

    @Override
    public String toString() {
        return toString(this, workerGroup, shutdownWorkerGroupOnClose, channelOptions,
                        sslContextCustomizer, addressResolverGroupFactory, http2InitialConnectionWindowSize,
                        http2InitialStreamWindowSize, http2MaxFrameSize, http2MaxHeaderListSize,
                        http1MaxInitialLineLength, http1MaxHeaderSize, http1MaxChunkSize, idleTimeoutMillis,
                        useHttp2Preface, useHttp1Pipelining, connectionPoolListener, meterRegistry);
    }

    static String toString(
            ClientFactoryBuilder self,
            EventLoopGroup workerGroup, boolean shutdownWorkerGroupOnClose,
            Map<ChannelOption<?>, Object> socketOptions,
            Consumer<? super SslContextBuilder> sslContextCustomizer,
            @Nullable
            Function<? super EventLoopGroup,
                     ? extends AddressResolverGroup<? extends InetSocketAddress>> addressResolverGroupFactory,
            int http2InitialConnectionWindowSize, int http2InitialStreamWindowSize, int http2MaxFrameSize,
            long http2MaxHeaderListSize, int http1MaxInitialLineLength, int http1MaxHeaderSize,
            int http1MaxChunkSize, long idleTimeoutMillis, boolean useHttp2Preface, boolean useHttp1Pipelining,
            ConnectionPoolListener connectionPoolListener,
            MeterRegistry meterRegistry) {

        final ToStringHelper helper = MoreObjects.toStringHelper(self).omitNullValues();
        helper.add("workerGroup", workerGroup + " (shutdownOnClose=" + shutdownWorkerGroupOnClose + ')')
              .add("socketOptions", socketOptions)
              .add("http2InitialConnectionWindowSize", http2InitialConnectionWindowSize)
              .add("http2InitialStreamWindowSize", http2InitialStreamWindowSize)
              .add("http2MaxFrameSize", http2MaxFrameSize)
              .add("http2MaxHeaderListSize", http2MaxHeaderListSize)
              .add("http1MaxInitialLineLength", http1MaxInitialLineLength)
              .add("http1MaxHeaderSize", http1MaxHeaderSize)
              .add("http1MaxChunkSize", http1MaxChunkSize)
              .add("idleTimeoutMillis", idleTimeoutMillis)
              .add("useHttp2Preface", useHttp2Preface)
              .add("useHttp1Pipelining", useHttp1Pipelining);

        if (connectionPoolListener != DEFAULT_CONNECTION_POOL_LISTENER) {
            helper.add("connectionPoolListener", connectionPoolListener);
        }

        if (sslContextCustomizer != DEFAULT_SSL_CONTEXT_CUSTOMIZER) {
            helper.add("sslContextCustomizer", sslContextCustomizer);
        }

        if (!(addressResolverGroupFactory instanceof DefaultAddressResolverGroupFactory)) {
            helper.add("addressResolverGroupFactory", addressResolverGroupFactory);
        }

        helper.add("meterRegistry", meterRegistry);

        return helper.toString();
    }
}

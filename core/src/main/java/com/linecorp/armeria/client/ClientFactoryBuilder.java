/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.pool.KeyedChannelPoolHandler;
import com.linecorp.armeria.client.pool.PoolKey;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.internal.TransportType;

import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;

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

    private static final KeyedChannelPoolHandler<PoolKey> DEFAULT_CONNECTION_POOL_LISTENER =
            KeyedChannelPoolHandler.noop();

    private static final Consumer<SslContextBuilder> DEFAULT_SSL_CONTEXT_CUSTOMIZER = b -> { /* no-op */ };

    private static final Function<EventLoopGroup, DnsAddressResolverGroup>
            DEFAULT_ADDRESS_RESOLVER_GROUP_FACTORY = eventLoopGroup -> new DnsAddressResolverGroup(
            TransportType.datagramChannelType(eventLoopGroup),
            DnsServerAddressStreamProviders.platformDefault());

    // Do not accept 1) the options that may break Armeria and 2) the deprecated options.
    @SuppressWarnings("deprecation")
    private static final Set<ChannelOption<?>> PROHIBITED_SOCKET_OPTIONS = ImmutableSet.of(
            ChannelOption.ALLOW_HALF_CLOSURE, ChannelOption.AUTO_READ,
            ChannelOption.AUTO_CLOSE, ChannelOption.MAX_MESSAGES_PER_READ,
            ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, ChannelOption.WRITE_BUFFER_LOW_WATER_MARK,
            EpollChannelOption.EPOLL_MODE);

    private static final String MESSAGE_MUTUALLY_EXCLUSIVE =
            "eventLoopGroup() and useDaemonThreads/numWorkerThreads() are mutually exclusive.";

    // Netty-related properties:
    private EventLoopGroup workerGroup = CommonPools.workerGroup();
    private boolean shutdownWorkerGroupOnClose;
    private final Map<ChannelOption<?>, Object> socketOptions = new HashMap<>();
    private Consumer<? super SslContextBuilder> sslContextCustomizer = DEFAULT_SSL_CONTEXT_CUSTOMIZER;
    private Function<? super EventLoopGroup,
            ? extends AddressResolverGroup<? extends InetSocketAddress>> addressResolverGroupFactory =
            DEFAULT_ADDRESS_RESOLVER_GROUP_FACTORY;

    // Armeria-related properties:
    private long idleTimeoutMillis = Flags.defaultClientIdleTimeoutMillis();
    private boolean useHttp2Preface = Flags.defaultUseHttp2Preface();
    private boolean useHttp1Pipelining = Flags.defaultUseHttp1Pipelining();
    private KeyedChannelPoolHandler<? super PoolKey> connectionPoolListener = DEFAULT_CONNECTION_POOL_LISTENER;

    /**
     * Creates a new instance.
     */
    public ClientFactoryBuilder() {
        connectTimeoutMillis(Flags.defaultConnectTimeoutMillis());
        socketOption(ChannelOption.SO_KEEPALIVE, true);
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
        return socketOption(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                            ConvertUtils.safeLongToInt(connectTimeoutMillis));
    }

    /**
     * Sets the options of sockets created by the {@link ClientFactory}.
     */
    public <T> ClientFactoryBuilder socketOption(ChannelOption<T> option, T value) {
        requireNonNull(option, "option");
        checkArgument(!PROHIBITED_SOCKET_OPTIONS.contains(option),
                      "prohibited socket option: %s", option);

        socketOptions.put(option, requireNonNull(value, "value"));
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
     */
    public ClientFactoryBuilder addressResolverGroupFactory(
            Function<? super EventLoopGroup,
                     ? extends AddressResolverGroup<? extends InetSocketAddress>> addressResolverGroupFactory) {
        this.addressResolverGroupFactory = requireNonNull(addressResolverGroupFactory,
                                                          "addressResolverGroupFactory");
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
     * HTTP/1 connections. This does not affect HTTP/2 connections. This option is enabled by default.
     */
    public ClientFactoryBuilder useHttp1Pipelining(boolean useHttp1Pipelining) {
        this.useHttp1Pipelining = useHttp1Pipelining;
        return this;
    }

    /**
     * Sets the listener which is notified on a connection pool event.
     */
    public ClientFactoryBuilder connectionPoolListener(
            KeyedChannelPoolHandler<? super PoolKey> connectionPoolListener) {
        this.connectionPoolListener = requireNonNull(connectionPoolListener, "connectionPoolListener");
        return this;
    }

    /**
     * Returns a newly-created {@link ClientFactory} based on the properties of this builder.
     */
    public ClientFactory build() {
        return new DefaultClientFactory(new HttpClientFactory(
                workerGroup, shutdownWorkerGroupOnClose, socketOptions, sslContextCustomizer,
                addressResolverGroupFactory, idleTimeoutMillis, useHttp2Preface, useHttp1Pipelining,
                connectionPoolListener));
    }

    @Override
    public String toString() {
        return toString(this, workerGroup, shutdownWorkerGroupOnClose, socketOptions,
                        sslContextCustomizer, addressResolverGroupFactory, idleTimeoutMillis,
                        useHttp2Preface, useHttp1Pipelining, connectionPoolListener);
    }

    static String toString(
            ClientFactoryBuilder self,
            EventLoopGroup workerGroup, boolean shutdownWorkerGroupOnClose,
            Map<ChannelOption<?>, Object> socketOptions,
            Consumer<? super SslContextBuilder> sslContextCustomizer,
            Function<? super EventLoopGroup,
                     ? extends AddressResolverGroup<? extends InetSocketAddress>> addressResolverGroupFactory,
            long idleTimeoutMillis,
            boolean useHttp2Preface,
            boolean useHttp1Pipelining,
            KeyedChannelPoolHandler<? super PoolKey> connectionPoolListener) {

        final ToStringHelper helper = MoreObjects.toStringHelper(self);
        helper.add("workerGroup", workerGroup + " (shutdownOnClose=" + shutdownWorkerGroupOnClose + ')')
              .add("socketOptions", socketOptions)
              .add("idleTimeoutMillis", idleTimeoutMillis)
              .add("useHttp2Preface", useHttp2Preface)
              .add("useHttp1Pipelining", useHttp1Pipelining);

        if (connectionPoolListener != DEFAULT_CONNECTION_POOL_LISTENER) {
            helper.add("connectionPoolListener", connectionPoolListener);
        }

        if (sslContextCustomizer != DEFAULT_SSL_CONTEXT_CUSTOMIZER) {
            helper.add("sslContextCustomizer", sslContextCustomizer);
        }

        if (addressResolverGroupFactory != DEFAULT_ADDRESS_RESOLVER_GROUP_FACTORY) {
            helper.add("addressResolverGroupFactory", addressResolverGroupFactory);
        }

        return helper.toString();
    }
}

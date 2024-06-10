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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.MAX_FRAME_SIZE_LOWER_BOUND;
import static io.netty.handler.codec.http2.Http2CodecUtil.MAX_FRAME_SIZE_UPPER_BOUND;
import static io.netty.handler.codec.http2.Http2CodecUtil.MAX_INITIAL_WINDOW_SIZE;
import static java.util.Objects.requireNonNull;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.client.proxy.ProxyConfig;
import com.linecorp.armeria.client.proxy.ProxyConfigSelector;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Http1HeaderNaming;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsSetters;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.RequestContextUtil;
import com.linecorp.armeria.internal.common.util.ChannelUtil;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;

/**
 * Builds a new {@link ClientFactory}.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * final ClientFactory factory =
 *         ClientFactory.builder()
 *                      // Set the connection timeout to 5 seconds.
 *                      .connectTimeoutMillis(5000)
 *                      // Set the socket send buffer to 1 MiB.
 *                      .socketOption(ChannelOption.SO_SNDBUF, 1048576)
 *                      // Disable certificate verification; never do this in production!
 *                      .tlsNoVerify()
 *                      .build();
 * }</pre>
 */
public final class ClientFactoryBuilder implements TlsSetters {

    private static final ClientFactoryOptionValue<Long> ZERO_PING_INTERVAL =
            ClientFactoryOptions.PING_INTERVAL_MILLIS.newValue(0L);

    @VisibleForTesting
    static final long MIN_PING_INTERVAL_MILLIS = 1000L;
    private static final ClientFactoryOptionValue<Long> MIN_PING_INTERVAL =
            ClientFactoryOptions.PING_INTERVAL_MILLIS.newValue(MIN_PING_INTERVAL_MILLIS);

    static final long MIN_MAX_CONNECTION_AGE_MILLIS = 1_000L;

    static {
        RequestContextUtil.init();
    }

    private final Map<ClientFactoryOption<?>, ClientFactoryOptionValue<?>> options = new LinkedHashMap<>();

    // Netty-related properties:
    @Nullable
    private Consumer<DnsResolverGroupBuilder> dnsResolverGroupCustomizer;

    // Armeria-related properties:
    private int maxNumEventLoopsPerEndpoint;
    private int maxNumEventLoopsPerHttp1Endpoint;
    private final List<ToIntFunction<Endpoint>> maxNumEventLoopsFunctions = new ArrayList<>();
    private boolean tlsNoVerifySet;
    private final Set<String> insecureHosts = new HashSet<>();

    ClientFactoryBuilder() {
        connectTimeoutMillis(Flags.defaultConnectTimeoutMillis());
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
        option(ClientFactoryOptions.WORKER_GROUP, requireNonNull(workerGroup, "workerGroup"));
        option(ClientFactoryOptions.SHUTDOWN_WORKER_GROUP_ON_CLOSE, shutdownOnClose);
        return this;
    }

    /**
     * Uses a newly created {@link EventLoopGroup} with the specified number of threads for
     * performing socket I/O and running {@link Client#execute(ClientRequestContext, Request)}.
     * The worker {@link EventLoopGroup} will be shut down when the {@link ClientFactory} is closed.
     *
     * @param numThreads the number of event loop threads
     */
    public ClientFactoryBuilder workerGroup(int numThreads) {
        return workerGroup(EventLoopGroups.newEventLoopGroup(numThreads), true);
    }

    /**
     * Sets the factory that creates an {@link EventLoopScheduler} which is responsible for assigning an
     * {@link EventLoop} to handle a connection to the specified {@link Endpoint}.
     */
    public ClientFactoryBuilder eventLoopSchedulerFactory(
            Function<? super EventLoopGroup, ? extends EventLoopScheduler> eventLoopSchedulerFactory) {
        requireNonNull(eventLoopSchedulerFactory, "eventLoopSchedulerFactory");
        checkState(maxNumEventLoopsPerHttp1Endpoint == 0 && maxNumEventLoopsPerEndpoint == 0 &&
                   maxNumEventLoopsFunctions.isEmpty(),
                   "Cannot set eventLoopSchedulerFactory when maxEventLoop per endpoint is specified.");
        option(ClientFactoryOptions.EVENT_LOOP_SCHEDULER_FACTORY, eventLoopSchedulerFactory);
        return this;
    }

    /**
     * Sets the maximum number of {@link EventLoop}s which will be used to handle HTTP/1.1 connections
     * except the ones specified by {@link #maxNumEventLoopsFunction(ToIntFunction)}.
     * {@value DefaultEventLoopScheduler#DEFAULT_MAX_NUM_EVENT_LOOPS} is used by default.
     */
    public ClientFactoryBuilder maxNumEventLoopsPerHttp1Endpoint(int maxNumEventLoopsPerEndpoint) {
        validateMaxNumEventLoopsPerEndpoint(maxNumEventLoopsPerEndpoint);
        maxNumEventLoopsPerHttp1Endpoint = maxNumEventLoopsPerEndpoint;
        return this;
    }

    /**
     * Sets the maximum number of {@link EventLoop}s which will be used to handle HTTP/2 connections
     * except the ones specified by {@link #maxNumEventLoopsFunction(ToIntFunction)}.
     * {@value DefaultEventLoopScheduler#DEFAULT_MAX_NUM_EVENT_LOOPS} is used by default.
     */
    public ClientFactoryBuilder maxNumEventLoopsPerEndpoint(int maxNumEventLoopsPerEndpoint) {
        validateMaxNumEventLoopsPerEndpoint(maxNumEventLoopsPerEndpoint);
        this.maxNumEventLoopsPerEndpoint = maxNumEventLoopsPerEndpoint;
        return this;
    }

    private void validateMaxNumEventLoopsPerEndpoint(int maxNumEventLoopsPerEndpoint) {
        checkArgument(maxNumEventLoopsPerEndpoint > 0,
                      "maxNumEventLoopsPerEndpoint: %s (expected: > 0)", maxNumEventLoopsPerEndpoint);
        checkState(!options.containsKey(ClientFactoryOptions.EVENT_LOOP_SCHEDULER_FACTORY),
                   "maxNumEventLoopsPerEndpoint() and eventLoopSchedulerFactory() are mutually exclusive.");
    }

    /**
     * Sets the {@link ToIntFunction} which takes an {@link Endpoint} and produces the maximum number of
     * {@link EventLoop}s which will be used to handle connections to the specified {@link Endpoint}.
     * The function should return {@code 0} or a negative value for the {@link Endpoint}s which it
     * doesn't want to handle. For example: <pre>{@code
     * ToIntFunction<Endpoint> function = endpoint -> {
     *     if (endpoint.equals(Endpoint.of("foo.com"))) {
     *         return 5;
     *     }
     *     if (endpoint.host().contains("bar.com")) {
     *         return Integer.MAX_VALUE; // The value will be clamped at the number of event loops.
     *     }
     *     return -1; // Should return 0 or a negative value to use the default value.
     * }
     * }</pre>
     */
    public ClientFactoryBuilder maxNumEventLoopsFunction(ToIntFunction<Endpoint> maxNumEventLoopsFunction) {
        checkState(!options.containsKey(ClientFactoryOptions.EVENT_LOOP_SCHEDULER_FACTORY),
                   "maxNumEventLoopsPerEndpoint() and eventLoopSchedulerFactory() are mutually exclusive.");
        maxNumEventLoopsFunctions.add(requireNonNull(maxNumEventLoopsFunction, "maxNumEventLoopsFunction"));
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
                             Ints.saturatedCast(connectTimeoutMillis));
    }

    /**
     * Sets the options of sockets created by the {@link ClientFactory}.
     */
    public <T> ClientFactoryBuilder channelOption(ChannelOption<T> option, T value) {
        requireNonNull(option, "option");
        requireNonNull(value, "value");
        channelOptions(ImmutableMap.of(option, value));
        return this;
    }

    private void channelOptions(Map<ChannelOption<?>, Object> newChannelOptions) {
        @SuppressWarnings("unchecked")
        final ClientFactoryOptionValue<Map<ChannelOption<?>, Object>> castOptions =
                (ClientFactoryOptionValue<Map<ChannelOption<?>, Object>>) options.get(
                        ClientFactoryOptions.CHANNEL_OPTIONS);
        if (castOptions == null) {
            options.put(ClientFactoryOptions.CHANNEL_OPTIONS,
                        ClientFactoryOptions.CHANNEL_OPTIONS.newValue(ImmutableMap.copyOf(newChannelOptions)));
        } else {
            final ImmutableMap.Builder<ChannelOption<?>, Object> builder =
                    ImmutableMap.builderWithExpectedSize(newChannelOptions.size());
            castOptions.value().forEach((channelOption, value) -> {
                if (!newChannelOptions.containsKey(channelOption)) {
                    builder.put(channelOption, value);
                }
            });
            builder.putAll(newChannelOptions);

            options.put(ClientFactoryOptions.CHANNEL_OPTIONS,
                        ClientFactoryOptions.CHANNEL_OPTIONS.newValue(builder.build()));
        }
    }

    /**
     * Disables the verification of server's TLS certificate chain. If you want to disable verification for
     * only specific hosts, use {@link #tlsNoVerifyHosts(String...)}.
     * <strong>Note:</strong> You should never use this in production but only for a testing purpose.
     *
     * @see InsecureTrustManagerFactory
     * @see #tlsCustomizer(Consumer)
     */
    public ClientFactoryBuilder tlsNoVerify() {
        checkState(insecureHosts.isEmpty(), "tlsNoVerify() and tlsNoVerifyHosts() are mutually exclusive.");
        tlsNoVerifySet = true;
        return this;
    }

    /**
     * Disables the verification of server's TLS certificate chain for specific hosts. If you want to disable
     * all verification, use {@link #tlsNoVerify()} .
     * <strong>Note:</strong> You should never use this in production but only for a testing purpose.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public ClientFactoryBuilder tlsNoVerifyHosts(String... insecureHosts) {
        checkState(!tlsNoVerifySet, "tlsNoVerify() and tlsNoVerifyHosts() are mutually exclusive.");
        this.insecureHosts.addAll(Arrays.asList(insecureHosts));
        return this;
    }

    /**
     * Configures SSL or TLS for client certificate authentication with the specified {@code keyCertChainFile}
     * and cleartext {@code keyFile}.
     */
    @Override
    public ClientFactoryBuilder tls(File keyCertChainFile, File keyFile) {
        return (ClientFactoryBuilder) TlsSetters.super.tls(keyCertChainFile, keyFile);
    }

    /**
     * Configures SSL or TLS for client certificate authentication with the specified {@code keyCertChainFile},
     * {@code keyFile} and {@code keyPassword}.
     */
    @Override
    public ClientFactoryBuilder tls(File keyCertChainFile, File keyFile, @Nullable String keyPassword) {
        requireNonNull(keyCertChainFile, "keyCertChainFile");
        requireNonNull(keyFile, "keyFile");
        return tlsCustomizer(customizer -> customizer.keyManager(keyCertChainFile, keyFile, keyPassword));
    }

    /**
     * Configures SSL or TLS for client certificate authentication with the specified
     * {@code keyCertChainInputStream} and cleartext {@code keyInputStream}.
     */
    @Override
    public ClientFactoryBuilder tls(InputStream keyCertChainInputStream, InputStream keyInputStream) {
        return (ClientFactoryBuilder) TlsSetters.super.tls(keyCertChainInputStream, keyInputStream);
    }

    /**
     * Configures SSL or TLS for client certificate authentication with the specified
     * {@code keyCertChainInputStream} and {@code keyInputStream} and {@code keyPassword}.
     */
    @Override
    public ClientFactoryBuilder tls(InputStream keyCertChainInputStream, InputStream keyInputStream,
                                    @Nullable String keyPassword) {
        requireNonNull(keyCertChainInputStream, "keyCertChainInputStream");
        requireNonNull(keyInputStream, "keyInputStream");

        // Retrieve the content of the given streams so that they can be consumed more than once.
        final byte[] keyCertChain;
        final byte[] key;
        try {
            keyCertChain = ByteStreams.toByteArray(keyCertChainInputStream);
            key = ByteStreams.toByteArray(keyInputStream);
        } catch (IOException e) {
            throw new IOError(e);
        }

        return tlsCustomizer(customizer -> customizer.keyManager(new ByteArrayInputStream(keyCertChain),
                                                                 new ByteArrayInputStream(key),
                                                                 keyPassword));
    }

    /**
     * Configures SSL or TLS for client certificate authentication with the specified cleartext
     * {@link PrivateKey} and {@link X509Certificate} chain.
     */
    @Override
    public ClientFactoryBuilder tls(PrivateKey key, X509Certificate... keyCertChain) {
        return (ClientFactoryBuilder) TlsSetters.super.tls(key, keyCertChain);
    }

    /**
     * Configures SSL or TLS for client certificate authentication with the specified cleartext
     * {@link PrivateKey} and {@link X509Certificate} chain.
     */
    @Override
    public ClientFactoryBuilder tls(PrivateKey key, Iterable<? extends X509Certificate> keyCertChain) {
        return (ClientFactoryBuilder) TlsSetters.super.tls(key, keyCertChain);
    }

    /**
     * Configures SSL or TLS for client certificate authentication with the specified {@link PrivateKey},
     * {@code keyPassword} and {@link X509Certificate} chain.
     */
    @Override
    public ClientFactoryBuilder tls(PrivateKey key, @Nullable String keyPassword,
                                    X509Certificate... keyCertChain) {
        return (ClientFactoryBuilder) TlsSetters.super.tls(key, keyPassword, keyCertChain);
    }

    /**
     * Configures SSL or TLS for client certificate authentication with the specified {@link PrivateKey},
     * {@code keyPassword} and {@link X509Certificate} chain.
     */
    @Override
    public ClientFactoryBuilder tls(PrivateKey key, @Nullable String keyPassword,
                                    Iterable<? extends X509Certificate> keyCertChain) {
        requireNonNull(key, "key");
        requireNonNull(keyCertChain, "keyCertChain");

        for (X509Certificate keyCert : keyCertChain) {
            requireNonNull(keyCert, "keyCertChain contains null.");
        }

        return tlsCustomizer(customizer -> customizer.keyManager(key, keyPassword, keyCertChain));
    }

    /**
     * Configures SSL or TLS for client certificate authentication with the specified {@link KeyManagerFactory}.
     */
    @Override
    public ClientFactoryBuilder tls(KeyManagerFactory keyManagerFactory) {
        requireNonNull(keyManagerFactory, "keyManagerFactory");
        return tlsCustomizer(customizer -> customizer.keyManager(keyManagerFactory));
    }

    /**
     * Adds the {@link Consumer} which can arbitrarily configure the {@link SslContextBuilder} that will be
     * applied to the SSL session. For example, use {@link SslContextBuilder#trustManager(TrustManagerFactory)}
     * to configure a custom server CA or {@link SslContextBuilder#keyManager(KeyManagerFactory)} to configure
     * a client certificate for SSL authorization.
     */
    @Override
    public ClientFactoryBuilder tlsCustomizer(Consumer<? super SslContextBuilder> tlsCustomizer) {
        requireNonNull(tlsCustomizer, "tlsCustomizer");
        @SuppressWarnings("unchecked")
        final ClientFactoryOptionValue<Consumer<? super SslContextBuilder>> oldTlsCustomizerValue =
                (ClientFactoryOptionValue<Consumer<? super SslContextBuilder>>)
                        options.get(ClientFactoryOptions.TLS_CUSTOMIZER);

        final Consumer<? super SslContextBuilder> oldTlsCustomizer =
                oldTlsCustomizerValue == null ? ClientFactoryOptions.TLS_CUSTOMIZER.defaultValue()
                                              : oldTlsCustomizerValue.value();
        if (oldTlsCustomizer == ClientFactoryOptions.TLS_CUSTOMIZER.defaultValue()) {
            option(ClientFactoryOptions.TLS_CUSTOMIZER, tlsCustomizer);
        } else {
            option(ClientFactoryOptions.TLS_CUSTOMIZER, b -> {
                oldTlsCustomizer.accept(b);
                tlsCustomizer.accept(b);
            });
        }
        return this;
    }

    /**
     * Allows the bad cipher suites listed in
     * <a href="https://datatracker.ietf.org/doc/html/rfc7540#appendix-A">RFC7540</a> for TLS handshake.
     *
     * <p>Note that enabling this option increases the security risk of your connection.
     * Use it only when you must communicate with a legacy system that does not support
     * secure cipher suites.
     * See <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-9.2.2">Section 9.2.2, RFC7540</a> for
     * more information. This option is disabled by default.
     *
     * @deprecated It's not recommended to enable this option. Use it only when you have no other way to
     *             communicate with an insecure peer than this.
     */
    @Deprecated
    public ClientFactoryBuilder tlsAllowUnsafeCiphers() {
        return tlsAllowUnsafeCiphers(true);
    }

    /**
     * Allows the bad cipher suites listed in
     * <a href="https://datatracker.ietf.org/doc/html/rfc7540#appendix-A">RFC7540</a> for TLS handshake.
     *
     * <p>Note that enabling this option increases the security risk of your connection.
     * Use it only when you must communicate with a legacy system that does not support
     * secure cipher suites.
     * See <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-9.2.2">Section 9.2.2, RFC7540</a> for
     * more information. This option is disabled by default.
     *
     * @param tlsAllowUnsafeCiphers Whether to allow the unsafe ciphers
     *
     * @deprecated It's not recommended to enable this option. Use it only when you have no other way to
     *             communicate with an insecure peer than this.
     */
    @Deprecated
    public ClientFactoryBuilder tlsAllowUnsafeCiphers(boolean tlsAllowUnsafeCiphers) {
        option(ClientFactoryOptions.TLS_ALLOW_UNSAFE_CIPHERS, tlsAllowUnsafeCiphers);
        return this;
    }

    /**
     * Sets the {@link TlsEngineType} that will be used for processing TLS connections.
     *
     * @param tlsEngineType the {@link TlsEngineType} to use
     */
    @UnstableApi
    public ClientFactoryBuilder tlsEngineType(TlsEngineType tlsEngineType) {
        option(ClientFactoryOptions.TLS_ENGINE_TYPE, tlsEngineType);
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
        checkState(dnsResolverGroupCustomizer == null,
                   "addressResolverGroupFactory() and domainNameResolverCustomizer() are mutually exclusive.");
        option(ClientFactoryOptions.ADDRESS_RESOLVER_GROUP_FACTORY, addressResolverGroupFactory);
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
            Consumer<? super DnsResolverGroupBuilder> dnsResolverGroupCustomizer) {
        requireNonNull(dnsResolverGroupCustomizer, "dnsResolverGroupCustomizer");
        checkState(!options.containsKey(ClientFactoryOptions.ADDRESS_RESOLVER_GROUP_FACTORY),
                   "addressResolverGroupFactory() and domainNameResolverCustomizer() are mutually exclusive.");
        if (this.dnsResolverGroupCustomizer == null) {
            //noinspection unchecked
            this.dnsResolverGroupCustomizer = (Consumer<DnsResolverGroupBuilder>) dnsResolverGroupCustomizer;
        } else {
            this.dnsResolverGroupCustomizer =
                    this.dnsResolverGroupCustomizer.andThen(dnsResolverGroupCustomizer);
        }
        return this;
    }

    /**
     * Sets the
     * <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-6.9.2">initial connection flow-control
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
        option(ClientFactoryOptions.HTTP2_INITIAL_CONNECTION_WINDOW_SIZE, http2InitialConnectionWindowSize);
        return this;
    }

    /**
     * Sets the
     * <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-6.5.2">SETTINGS_INITIAL_WINDOW_SIZE</a>
     * for HTTP/2 stream-level flow control. Note that this setting affects the window size of all streams,
     * not the connection-level window size.
     *
     * @see #http2InitialConnectionWindowSize(int)
     */
    public ClientFactoryBuilder http2InitialStreamWindowSize(int http2InitialStreamWindowSize) {
        checkArgument(http2InitialStreamWindowSize > 0,
                      "http2InitialStreamWindowSize: %s (expected: > 0 and <= %s)",
                      http2InitialStreamWindowSize, MAX_INITIAL_WINDOW_SIZE);
        option(ClientFactoryOptions.HTTP2_INITIAL_STREAM_WINDOW_SIZE, http2InitialStreamWindowSize);
        return this;
    }

    /**
     * Sets the
     * <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-6.5.2">SETTINGS_MAX_FRAME_SIZE</a>
     * that indicates the size of the largest frame payload that this client is willing to receive.
     */
    public ClientFactoryBuilder http2MaxFrameSize(int http2MaxFrameSize) {
        checkArgument(http2MaxFrameSize >= MAX_FRAME_SIZE_LOWER_BOUND &&
                      http2MaxFrameSize <= MAX_FRAME_SIZE_UPPER_BOUND,
                      "http2MaxFrameSize: %s (expected: >= %s and <= %s)",
                      http2MaxFrameSize, MAX_FRAME_SIZE_LOWER_BOUND, MAX_FRAME_SIZE_UPPER_BOUND);
        option(ClientFactoryOptions.HTTP2_MAX_FRAME_SIZE, http2MaxFrameSize);
        return this;
    }

    /**
     * Sets the
     * <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-6.5.2">SETTINGS_MAX_HEADER_LIST_SIZE</a>
     * that indicates the maximum size of header list that the client is prepared to accept, in octets.
     */
    public ClientFactoryBuilder http2MaxHeaderListSize(long http2MaxHeaderListSize) {
        checkArgument(http2MaxHeaderListSize > 0 &&
                      http2MaxHeaderListSize <= 0xFFFFFFFFL,
                      "http2MaxHeaderListSize: %s (expected: a positive 32-bit unsigned integer)",
                      http2MaxHeaderListSize);
        option(ClientFactoryOptions.HTTP2_MAX_HEADER_LIST_SIZE, http2MaxHeaderListSize);
        return this;
    }

    /**
     * Sets the maximum length of an HTTP/1 response initial line.
     */
    public ClientFactoryBuilder http1MaxInitialLineLength(int http1MaxInitialLineLength) {
        checkArgument(http1MaxInitialLineLength >= 0,
                      "http1MaxInitialLineLength: %s (expected: >= 0)",
                      http1MaxInitialLineLength);
        option(ClientFactoryOptions.HTTP1_MAX_INITIAL_LINE_LENGTH, http1MaxInitialLineLength);
        return this;
    }

    /**
     * Sets the maximum length of all headers in an HTTP/1 response.
     */
    public ClientFactoryBuilder http1MaxHeaderSize(int http1MaxHeaderSize) {
        checkArgument(http1MaxHeaderSize >= 0,
                      "http1MaxHeaderSize: %s (expected: >= 0)",
                      http1MaxHeaderSize);
        option(ClientFactoryOptions.HTTP1_MAX_HEADER_SIZE, http1MaxHeaderSize);
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
        option(ClientFactoryOptions.HTTP1_MAX_CHUNK_SIZE, http1MaxChunkSize);
        return this;
    }

    /**
     * Sets the idle timeout of a socket connection. The connection is closed if there is no request in
     * progress for the given amount of time. By default, HTTP/2 PING frames do not prevent connection from
     * closing. Use the method {@link ClientFactoryBuilder#idleTimeout(Duration, boolean)} to set whether to
     * prevent connection from closing when an HTTP/2 PING frame or the response of {@code "OPTIONS * HTTP/1.1"}
     * is received.
     */
    public ClientFactoryBuilder idleTimeout(Duration idleTimeout) {
        return idleTimeoutMillis(requireNonNull(idleTimeout, "idleTimeout").toMillis());
    }

    /**
     * Sets the idle timeout of a socket connection. The connection is closed if there is no request in
     * progress for the given amount of time. If {@code keepAliveOnPing} is true, the idle timeout is reset
     * when an HTTP/2 PING frame or the response of {@code "OPTIONS * HTTP/1.1"} is received.
     */
    @UnstableApi
    public ClientFactoryBuilder idleTimeout(Duration idleTimeout, boolean keepAliveOnPing) {
        return idleTimeoutMillis(requireNonNull(idleTimeout, "idleTimeout").toMillis(), keepAliveOnPing);
    }

    /**
     * Sets the idle timeout of a socket connection in milliseconds. The connection is closed if there is no
     * request in progress for the given amount of time.
     */
    public ClientFactoryBuilder idleTimeoutMillis(long idleTimeoutMillis) {
        return idleTimeoutMillis(idleTimeoutMillis, Flags.defaultClientKeepAliveOnPing());
    }

    /**
     * Sets the idle timeout of a socket connection. The connection is closed if there is no request in
     * progress for the given amount of time. If {@code keepAliveOnPing} is true, the idle timeout is reset
     * when an HTTP/2 PING frame or the response of {@code "OPTIONS * HTTP/1.1"} is received.
     */
    @UnstableApi
    public ClientFactoryBuilder idleTimeoutMillis(long idleTimeoutMillis, boolean keepAliveOnPing) {
        checkArgument(idleTimeoutMillis >= 0, "idleTimeoutMillis: %s (expected: >= 0)", idleTimeoutMillis);
        option(ClientFactoryOptions.IDLE_TIMEOUT_MILLIS, idleTimeoutMillis);
        option(ClientFactoryOptions.KEEP_ALIVE_ON_PING, keepAliveOnPing);
        return this;
    }

    /**
     * Sets the PING interval in milliseconds.
     * When neither read nor write was performed for the given {@code pingIntervalMillis},
     * a <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-6.7">PING</a> frame is sent for HTTP/2
     * or an <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-4.3.7">OPTIONS</a> request with
     * an asterisk ("*") is sent for HTTP/1.
     *
     * <p>Note that this settings is only in effect when {@link #idleTimeoutMillis(long)}} or
     * {@link #idleTimeout(Duration)} is greater than the specified PING interval.
     *
     * <p>The minimum allowed PING interval is {@value #MIN_PING_INTERVAL_MILLIS} milliseconds.
     * {@code 0} means the client will not send a PING.
     *
     * @throws IllegalArgumentException if the specified {@code pingIntervalMillis} is smaller than
     *                                  {@value #MIN_PING_INTERVAL_MILLIS} milliseconds.
     */
    public ClientFactoryBuilder pingIntervalMillis(long pingIntervalMillis) {
        checkArgument(pingIntervalMillis == 0 || pingIntervalMillis >= MIN_PING_INTERVAL_MILLIS,
                      "pingIntervalMillis: %s (expected: >= %s or == 0)", pingIntervalMillis,
                      MIN_PING_INTERVAL_MILLIS);
        option(ClientFactoryOptions.PING_INTERVAL_MILLIS, pingIntervalMillis);
        return this;
    }

    /**
     * Sets the PING interval.
     * When neither read nor write was performed for the given {@code pingInterval},
     * a <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-6.7">PING</a> frame is sent for HTTP/2
     * or an <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-4.3.7">OPTIONS</a> request with
     * an asterisk ("*") is sent for HTTP/1.
     *
     * <p>Note that this settings is only in effect when {@link #idleTimeoutMillis(long)}} or
     * {@link #idleTimeout(Duration)} is greater than the specified PING interval.
     *
     * <p>The minimum allowed PING interval is {@value #MIN_PING_INTERVAL_MILLIS} milliseconds.
     * {@code 0} means the client will not send a PING.
     *
     * @throws IllegalArgumentException if the specified {@code pingInterval} is smaller than
     *                                  {@value #MIN_PING_INTERVAL_MILLIS} milliseconds.
     */
    public ClientFactoryBuilder pingInterval(Duration pingInterval) {
        pingIntervalMillis(requireNonNull(pingInterval, "pingInterval").toMillis());
        return this;
    }

    /**
     * Sets the maximum allowed age of a connection in millis for keep-alive. A connection is disconnected
     * after the specified {@code maxConnectionAgeMillis} since the connection was established.
     * This option is disabled by default, which means unlimited.
     *
     * @param maxConnectionAgeMillis the maximum connection age in millis. {@code 0} disables the limit.
     * @throws IllegalArgumentException if the specified {@code maxConnectionAgeMillis} is smaller than
     *                                  {@value #MIN_MAX_CONNECTION_AGE_MILLIS} milliseconds.
     */
    public ClientFactoryBuilder maxConnectionAgeMillis(long maxConnectionAgeMillis) {
        checkArgument(maxConnectionAgeMillis >= MIN_MAX_CONNECTION_AGE_MILLIS || maxConnectionAgeMillis == 0,
                      "maxConnectionAgeMillis: %s (expected: >= %s or == 0)",
                      maxConnectionAgeMillis, MIN_MAX_CONNECTION_AGE_MILLIS);
        option(ClientFactoryOptions.MAX_CONNECTION_AGE_MILLIS, maxConnectionAgeMillis);
        return this;
    }

    /**
     * Sets the maximum allowed age of a connection for keep-alive. A connection is disconnected
     * after the specified {@code maxConnectionAge} since the connection was established.
     * This option is disabled by default, which means unlimited.
     *
     * @param maxConnectionAge the maximum connection age. {@code 0} disables the limit.
     * @throws IllegalArgumentException if the specified {@code maxConnectionAge} is smaller than
     *                                  {@value #MIN_MAX_CONNECTION_AGE_MILLIS} milliseconds.
     */
    public ClientFactoryBuilder maxConnectionAge(Duration maxConnectionAge) {
        return maxConnectionAgeMillis(requireNonNull(maxConnectionAge, "maxConnectionAge").toMillis());
    }

    /**
     * Sets the maximum allowed number of requests that can be sent through one connection.
     * This option is disabled by default, which means unlimited.
     *
     * @param maxNumRequestsPerConnection the maximum number of requests per connection.
     *                                    {@code 0} disables the limit.
     */
    public ClientFactoryBuilder maxNumRequestsPerConnection(int maxNumRequestsPerConnection) {
        checkArgument(maxNumRequestsPerConnection >= 0, "maxNumRequestsPerConnection: %s (expected: >= 0)",
                      maxNumRequestsPerConnection);
        option(ClientFactoryOptions.MAX_NUM_REQUESTS_PER_CONNECTION, maxNumRequestsPerConnection);
        return this;
    }

    /**
     * Sets whether to send an HTTP/2 preface string instead of an HTTP/1 upgrade request to negotiate
     * the protocol version of a cleartext HTTP connection.
     */
    public ClientFactoryBuilder useHttp2Preface(boolean useHttp2Preface) {
        option(ClientFactoryOptions.USE_HTTP2_PREFACE, useHttp2Preface);
        return this;
    }

    /**
     * Sets whether to use HTTP/1.1 instead of HTTP/2. If enabled, the client will not attempt to upgrade to
     * HTTP/2 for {@link SessionProtocol#HTTP} and {@link SessionProtocol#HTTPS}. However, the client will use
     * HTTP/2 if {@link SessionProtocol#H2} or {@link SessionProtocol#H2C} is used.
     * This option is disabled by default.
     */
    @UnstableApi
    public ClientFactoryBuilder preferHttp1(boolean preferHttp1) {
        option(ClientFactoryOptions.PREFER_HTTP1, preferHttp1);
        return this;
    }

    /**
     * Sets whether to use HTTP/2 without ALPN. This is useful if you want to communicate with an HTTP/2
     * server over TLS but the server does not support ALPN.
     */
    @UnstableApi
    public ClientFactoryBuilder useHttp2WithoutAlpn(boolean useHttp2WithoutAlpn) {
        option(ClientFactoryOptions.USE_HTTP2_WITHOUT_ALPN, useHttp2WithoutAlpn);
        return this;
    }

    /**
     * Sets whether to use <a href="https://en.wikipedia.org/wiki/HTTP_pipelining">HTTP pipelining</a> for
     * HTTP/1 connections. This does not affect HTTP/2 connections. This option is disabled by default.
     */
    public ClientFactoryBuilder useHttp1Pipelining(boolean useHttp1Pipelining) {
        option(ClientFactoryOptions.USE_HTTP1_PIPELINING, useHttp1Pipelining);
        return this;
    }

    /**
     * Sets the listener which is notified on a connection pool event.
     */
    public ClientFactoryBuilder connectionPoolListener(
            ConnectionPoolListener connectionPoolListener) {
        option(ClientFactoryOptions.CONNECTION_POOL_LISTENER,
               requireNonNull(connectionPoolListener, "connectionPoolListener"));
        return this;
    }

    /**
     * Sets the graceful connection shutdown timeout in milliseconds.
     * {@code 0} disables the timeout and closes the connection immediately after sending a GOAWAY frame.
     */
    public ClientFactoryBuilder http2GracefulShutdownTimeout(Duration duration) {
        requireNonNull(duration, "duration");
        return http2GracefulShutdownTimeoutMillis(duration.toMillis());
    }

    /**
     * Sets the graceful connection shutdown timeout in milliseconds.
     * {@code 0} disables the timeout and closes the connection immediately after sending a GOAWAY frame.
     */
    public ClientFactoryBuilder http2GracefulShutdownTimeoutMillis(long http2GracefulShutdownTimeoutMillis) {
        checkArgument(http2GracefulShutdownTimeoutMillis >= 0,
                      "http2GracefulShutdownTimeoutMillis: %s (expected: >= 0)",
                      http2GracefulShutdownTimeoutMillis);
        option(ClientFactoryOptions.HTTP2_GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS,
               http2GracefulShutdownTimeoutMillis);
        return this;
    }

    /**
     * Sets the {@link MeterRegistry} which collects various stats.
     */
    public ClientFactoryBuilder meterRegistry(MeterRegistry meterRegistry) {
        option(ClientFactoryOptions.METER_REGISTRY, requireNonNull(meterRegistry, "meterRegistry"));
        return this;
    }

    /**
     * Sets the {@link ProxyConfig} which contains proxy related configuration.
     */
    public ClientFactoryBuilder proxyConfig(ProxyConfig proxyConfig) {
        requireNonNull(proxyConfig, "proxyConfig");
        option(ClientFactoryOptions.PROXY_CONFIG_SELECTOR, ProxyConfigSelector.of(proxyConfig));
        return this;
    }

    /**
     * Sets the {@link ProxySelector} which determines the {@link ProxyConfig} to be used.
     *
     * <p>This method makes a best effort to provide compatibility with {@link ProxySelector},
     * but it has some limitations. See {@link ProxyConfigSelector#of(ProxySelector)} for more information.
     */
    public ClientFactoryBuilder proxyConfig(ProxySelector proxySelector) {
        requireNonNull(proxySelector, "proxySelector");
        option(ClientFactoryOptions.PROXY_CONFIG_SELECTOR, ProxyConfigSelector.of(proxySelector));
        return this;
    }

    /**
     * Sets the {@link ProxyConfigSelector} which determines the {@link ProxyConfig} to be used.
     */
    public ClientFactoryBuilder proxyConfig(ProxyConfigSelector proxyConfigSelector) {
        requireNonNull(proxyConfigSelector, "proxyConfigSelector");
        option(ClientFactoryOptions.PROXY_CONFIG_SELECTOR, proxyConfigSelector);
        return this;
    }

    /**
     * Sets the {@link Http1HeaderNaming} which converts a lower-cased HTTP/2 header name into
     * another HTTP/1 header name. This is useful when communicating with a legacy system that only supports
     * case-sensitive HTTP/1 headers.
     */
    public ClientFactoryBuilder http1HeaderNaming(Http1HeaderNaming http1HeaderNaming) {
        requireNonNull(http1HeaderNaming, "http1HeaderNaming");
        option(ClientFactoryOptions.HTTP1_HEADER_NAMING, http1HeaderNaming);
        return this;
    }

    /**
     * Adds the specified {@link ClientFactoryOption} and its {@code value}.
     */
    public <T> ClientFactoryBuilder option(ClientFactoryOption<T> option, T value) {
        requireNonNull(option, "option");
        requireNonNull(value, "value");
        return option(option.newValue(value));
    }

    /**
     * Adds the specified {@link ClientFactoryOptionValue}.
     */
    public <T> ClientFactoryBuilder option(ClientFactoryOptionValue<T> optionValue) {
        requireNonNull(optionValue, "optionValue");
        if (ClientFactoryOptions.CHANNEL_OPTIONS == optionValue.option()) {
            @SuppressWarnings("unchecked")
            final Map<ChannelOption<?>, Object> channelOptions =
                    (Map<ChannelOption<?>, Object>) optionValue.value();
            channelOptions(channelOptions);
        } else {
            options.put(optionValue.option(), optionValue);
        }
        return this;
    }

    /**
     * Adds the specified {@link ClientFactoryOptions}.
     */
    public ClientFactoryBuilder options(ClientFactoryOptions options) {
        requireNonNull(options, "options");
        options.forEach(this::option);
        return this;
    }

    private ClientFactoryOptions buildOptions() {
        options.computeIfAbsent(ClientFactoryOptions.EVENT_LOOP_SCHEDULER_FACTORY, k -> {
            final Function<? super EventLoopGroup, ? extends EventLoopScheduler> eventLoopSchedulerFactory =
                    eventLoopGroup -> new DefaultEventLoopScheduler(
                            eventLoopGroup, maxNumEventLoopsPerEndpoint, maxNumEventLoopsPerHttp1Endpoint,
                            maxNumEventLoopsFunctions);
            return ClientFactoryOptions.EVENT_LOOP_SCHEDULER_FACTORY.newValue(eventLoopSchedulerFactory);
        });

        options.computeIfAbsent(ClientFactoryOptions.ADDRESS_RESOLVER_GROUP_FACTORY, k -> {
            final Function<? super EventLoopGroup,
                    ? extends AddressResolverGroup<? extends InetSocketAddress>> addressResolverGroupFactory =
                    eventLoopGroup -> {
                        // FIXME(ikhoon): Remove DefaultAddressResolverGroup registration after fixing Window
                        //                domain name resolution failure.
                        //                https://github.com/line/armeria/issues/2243
                        if (Flags.useJdkDnsResolver() && dnsResolverGroupCustomizer == null) {
                            return DefaultAddressResolverGroup.INSTANCE;
                        }

                        final DnsResolverGroupBuilder builder = new DnsResolverGroupBuilder();
                        if (dnsResolverGroupCustomizer != null) {
                            dnsResolverGroupCustomizer.accept(builder);
                        }

                        if (builder.meterRegistry0() == null) {
                            final ClientFactoryOptionValue<?> opt = options.getOrDefault(
                                    ClientFactoryOptions.METER_REGISTRY,
                                    ClientFactoryOptions.METER_REGISTRY.newValue(
                                            ClientFactoryOptions.of().meterRegistry()));
                            builder.meterRegistry((MeterRegistry) opt.value());
                        }
                        return builder.build(eventLoopGroup);
                    };
            return ClientFactoryOptions.ADDRESS_RESOLVER_GROUP_FACTORY.newValue(addressResolverGroupFactory);
        });

        if (tlsNoVerifySet) {
            tlsCustomizer(b -> b.trustManager(InsecureTrustManagerFactory.INSTANCE));
        } else if (!insecureHosts.isEmpty()) {
            tlsCustomizer(b -> b.trustManager(IgnoreHostsTrustManager.of(insecureHosts)));
        }

        final ClientFactoryOptions newOptions = ClientFactoryOptions.of(options.values());
        final long maxConnectionAgeMillis = newOptions.maxConnectionAgeMillis();
        long idleTimeoutMillis = newOptions.idleTimeoutMillis();
        long pingIntervalMillis = newOptions.pingIntervalMillis();
        final ImmutableList.Builder<ClientFactoryOptionValue<?>> adjustedOptionsBuilder =
                ImmutableList.builderWithExpectedSize(2);

        if (maxConnectionAgeMillis != 0 && idleTimeoutMillis > maxConnectionAgeMillis) {
            adjustedOptionsBuilder
                    .add(ClientFactoryOptions.IDLE_TIMEOUT_MILLIS.newValue(maxConnectionAgeMillis));
            idleTimeoutMillis = maxConnectionAgeMillis;
        }

        if (idleTimeoutMillis > 0 && pingIntervalMillis > 0) {
            final long clampedPingIntervalMillis = Math.max(pingIntervalMillis, MIN_PING_INTERVAL_MILLIS);
            if (clampedPingIntervalMillis >= idleTimeoutMillis) {
                adjustedOptionsBuilder.add(ZERO_PING_INTERVAL);
                pingIntervalMillis = 0;
            } else if (pingIntervalMillis == MIN_PING_INTERVAL_MILLIS) {
                // no-op, clampedPingIntervalMillis is equal to pingIntervalMillis
            } else if (clampedPingIntervalMillis == MIN_PING_INTERVAL_MILLIS) {
                adjustedOptionsBuilder.add(MIN_PING_INTERVAL);
                pingIntervalMillis = MIN_PING_INTERVAL_MILLIS;
            }
        }

        final Map<ChannelOption<?>, Object> newChannelOptions =
                ChannelUtil.applyDefaultChannelOptions(
                        newOptions.channelOptions(), idleTimeoutMillis, pingIntervalMillis);
        adjustedOptionsBuilder.add(ClientFactoryOptions.CHANNEL_OPTIONS.newValue(newChannelOptions));

        final List<ClientFactoryOptionValue<?>> adjustedOptions = adjustedOptionsBuilder.build();
        if (!adjustedOptions.isEmpty()) {
            return ClientFactoryOptions.of(newOptions, adjustedOptions);
        } else {
            return newOptions;
        }
    }

    /**
     * Returns a newly-created {@link ClientFactory} based on the properties of this builder.
     */
    public ClientFactory build() {
        return new DefaultClientFactory(new HttpClientFactory(buildOptions()));
    }

    @Override
    public String toString() {
        final ToStringHelper helper = MoreObjects.toStringHelper(this).omitNullValues();
        helper.add("options", options);

        if (maxNumEventLoopsPerHttp1Endpoint > 0) {
            helper.add("maxNumEventLoopsPerHttp1Endpoint", maxNumEventLoopsPerHttp1Endpoint);
        }
        if (maxNumEventLoopsPerEndpoint > 0) {
            helper.add("maxNumEventLoopsPerEndpoint", maxNumEventLoopsPerEndpoint);
        }
        if (!maxNumEventLoopsFunctions.isEmpty()) {
            helper.add("maxNumEventLoopsFunctions", maxNumEventLoopsFunctions);
        }

        return helper.toString();
    }
}

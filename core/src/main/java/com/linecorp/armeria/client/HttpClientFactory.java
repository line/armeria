/*
 * Copyright 2016 LINE Corporation
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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.MapMaker;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.proxy.ProxyConfigSelector;
import com.linecorp.armeria.common.Http1HeaderNaming;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;
import com.linecorp.armeria.common.util.ReleasableHolder;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.internal.common.util.SslContextUtil;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.FutureListener;
import reactor.core.scheduler.NonBlocking;

/**
 * A {@link ClientFactory} that creates an HTTP client.
 */
final class HttpClientFactory implements ClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(HttpClientFactory.class);

    private static final CompletableFuture<?>[] EMPTY_FUTURES = new CompletableFuture[0];

    private static final Set<Scheme> SUPPORTED_SCHEMES =
            Arrays.stream(SessionProtocol.values())
                  .map(p -> Scheme.of(SerializationFormat.NONE, p))
                  .collect(toImmutableSet());

    private final EventLoopGroup workerGroup;
    private final boolean shutdownWorkerGroupOnClose;
    private final Bootstrap baseBootstrap;
    private final SslContext sslCtxHttp1Or2;
    private final SslContext sslCtxHttp1Only;
    private final AddressResolverGroup<InetSocketAddress> addressResolverGroup;
    private final int http2InitialConnectionWindowSize;
    private final int http2InitialStreamWindowSize;
    private final int http2MaxFrameSize;
    private final long http2MaxHeaderListSize;
    private final int http1MaxInitialLineLength;
    private final int http1MaxHeaderSize;
    private final int http1MaxChunkSize;
    private final long idleTimeoutMillis;
    private final long pingIntervalMillis;
    private final long maxConnectionAgeMillis;
    private final int maxNumRequestsPerConnection;
    private final boolean useHttp2Preface;
    private final boolean useHttp1Pipelining;
    private final ConnectionPoolListener connectionPoolListener;
    private MeterRegistry meterRegistry;
    private final ProxyConfigSelector proxyConfigSelector;
    private final Http1HeaderNaming http1HeaderNaming;

    private final ConcurrentMap<EventLoop, HttpChannelPool> pools = new MapMaker().weakKeys().makeMap();
    private final HttpClientDelegate clientDelegate;

    private final EventLoopScheduler eventLoopScheduler;
    private final Supplier<EventLoop> eventLoopSupplier =
            () -> RequestContext.mapCurrent(
                    ctx -> ctx.eventLoop().withoutContext(), () -> eventLoopGroup().next());
    private final ClientFactoryOptions options;
    private final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(this::closeAsync);

    HttpClientFactory(ClientFactoryOptions options) {
        workerGroup = options.workerGroup();

        @SuppressWarnings("unchecked")
        final AddressResolverGroup<InetSocketAddress> group =
                (AddressResolverGroup<InetSocketAddress>) options.addressResolverGroupFactory()
                                                                 .apply(workerGroup);
        addressResolverGroup = group;

        final Bootstrap bootstrap = new Bootstrap();
        bootstrap.channel(TransportType.socketChannelType(workerGroup));
        bootstrap.resolver(addressResolverGroup);

        options.channelOptions().forEach((option, value) -> {
            @SuppressWarnings("unchecked")
            final ChannelOption<Object> castOption = (ChannelOption<Object>) option;
            bootstrap.option(castOption, value);
        });

        final ImmutableList<? extends Consumer<? super SslContextBuilder>> tlsCustomizers =
                ImmutableList.of(options.tlsCustomizer());
        final boolean tlsAllowUnsafeCiphers = options.tlsAllowUnsafeCiphers();

        shutdownWorkerGroupOnClose = options.shutdownWorkerGroupOnClose();
        eventLoopScheduler = options.eventLoopSchedulerFactory().apply(workerGroup);
        baseBootstrap = bootstrap;
        sslCtxHttp1Or2 = SslContextUtil
                .createSslContext(SslContextBuilder::forClient, false, tlsAllowUnsafeCiphers, tlsCustomizers);
        sslCtxHttp1Only = SslContextUtil
                .createSslContext(SslContextBuilder::forClient, true, tlsAllowUnsafeCiphers, tlsCustomizers);
        http2InitialConnectionWindowSize = options.http2InitialConnectionWindowSize();
        http2InitialStreamWindowSize = options.http2InitialStreamWindowSize();
        http2MaxFrameSize = options.http2MaxFrameSize();
        http2MaxHeaderListSize = options.http2MaxHeaderListSize();
        pingIntervalMillis = options.pingIntervalMillis();
        http1MaxInitialLineLength = options.http1MaxInitialLineLength();
        http1MaxHeaderSize = options.http1MaxHeaderSize();
        http1MaxChunkSize = options.http1MaxChunkSize();
        idleTimeoutMillis = options.idleTimeoutMillis();
        useHttp2Preface = options.useHttp2Preface();
        useHttp1Pipelining = options.useHttp1Pipelining();
        connectionPoolListener = options.connectionPoolListener();
        meterRegistry = options.meterRegistry();
        proxyConfigSelector = options.proxyConfigSelector();
        http1HeaderNaming = options.http1HeaderNaming();
        maxConnectionAgeMillis = options.maxConnectionAgeMillis();
        maxNumRequestsPerConnection = options.maxNumRequestsPerConnection();

        this.options = options;

        clientDelegate = new HttpClientDelegate(this, addressResolverGroup);
    }

    /**
     * Returns a new {@link Bootstrap} whose {@link ChannelFactory}, {@link AddressResolverGroup} and
     * socket options are pre-configured.
     */
    Bootstrap newBootstrap() {
        return baseBootstrap.clone();
    }

    int http2InitialConnectionWindowSize() {
        return http2InitialConnectionWindowSize;
    }

    int http2InitialStreamWindowSize() {
        return http2InitialStreamWindowSize;
    }

    int http2MaxFrameSize() {
        return http2MaxFrameSize;
    }

    long http2MaxHeaderListSize() {
        return http2MaxHeaderListSize;
    }

    int http1MaxInitialLineLength() {
        return http1MaxInitialLineLength;
    }

    int http1MaxHeaderSize() {
        return http1MaxHeaderSize;
    }

    int http1MaxChunkSize() {
        return http1MaxChunkSize;
    }

    long idleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    long pingIntervalMillis() {
        return pingIntervalMillis;
    }

    long maxConnectionAgeMillis() {
        return maxConnectionAgeMillis;
    }

    int maxNumRequestsPerConnection() {
        return maxNumRequestsPerConnection;
    }

    boolean useHttp2Preface() {
        return useHttp2Preface;
    }

    boolean useHttp1Pipelining() {
        return useHttp1Pipelining;
    }

    ConnectionPoolListener connectionPoolListener() {
        return connectionPoolListener;
    }

    ProxyConfigSelector proxyConfigSelector() {
        return proxyConfigSelector;
    }

    Http1HeaderNaming http1HeaderNaming() {
        return http1HeaderNaming;
    }

    @VisibleForTesting
    AddressResolverGroup<InetSocketAddress> addressResolverGroup() {
        return addressResolverGroup;
    }

    @Override
    public Set<Scheme> supportedSchemes() {
        return SUPPORTED_SCHEMES;
    }

    @Override
    public EventLoopGroup eventLoopGroup() {
        return workerGroup;
    }

    @Override
    public Supplier<EventLoop> eventLoopSupplier() {
        return eventLoopSupplier;
    }

    @Override
    public ReleasableHolder<EventLoop> acquireEventLoop(SessionProtocol sessionProtocol,
                                                        EndpointGroup endpointGroup,
                                                        @Nullable Endpoint endpoint) {
        return eventLoopScheduler.acquire(sessionProtocol, endpointGroup, endpoint);
    }

    @Override
    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    @Override
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
    }

    @Override
    public ClientFactoryOptions options() {
        return options;
    }

    @Override
    public Object newClient(ClientBuilderParams params) {
        validateParams(params);

        final Class<?> clientType = params.clientType();
        validateClientType(clientType);

        final HttpClient delegate = params.options().decoration().decorate(clientDelegate);

        if (clientType == HttpClient.class) {
            return delegate;
        }

        if (clientType == WebClient.class) {
            return new DefaultWebClient(params, delegate, meterRegistry);
        } else {
            throw new IllegalArgumentException("unsupported client type: " + clientType.getName());
        }
    }

    private static Class<?> validateClientType(Class<?> clientType) {
        if (clientType != WebClient.class && clientType != HttpClient.class) {
            throw new IllegalArgumentException(
                    "clientType: " + clientType +
                    " (expected: " + WebClient.class.getSimpleName() + " or " +
                    HttpClient.class.getSimpleName() + ')');
        }

        return clientType;
    }

    @Override
    public boolean isClosing() {
        return closeable.isClosing();
    }

    @Override
    public boolean isClosed() {
        return closeable.isClosed();
    }

    @Override
    public CompletableFuture<?> whenClosed() {
        return closeable.whenClosed();
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return closeable.closeAsync();
    }

    private void closeAsync(CompletableFuture<?> future) {
        final List<CompletableFuture<?>> dependencies = new ArrayList<>(pools.size());
        for (final Iterator<HttpChannelPool> i = pools.values().iterator(); i.hasNext();) {
            dependencies.add(i.next().closeAsync());
            i.remove();
        }

        addressResolverGroup.close();

        CompletableFuture.allOf(dependencies.toArray(EMPTY_FUTURES)).handle((unused, cause) -> {
            if (cause != null) {
                logger.warn("Failed to close {}s:", HttpChannelPool.class.getSimpleName(), cause);
            }

            if (shutdownWorkerGroupOnClose) {
                workerGroup.shutdownGracefully().addListener((FutureListener<Object>) f -> {
                    if (f.cause() != null) {
                        logger.warn("Failed to shut down a worker group:", f.cause());
                    }
                    future.complete(null);
                });
            } else {
                future.complete(null);
            }
            return null;
        });
    }

    @Override
    public void close() {
        if (Thread.currentThread() instanceof NonBlocking) {
            // Avoid blocking operation if we're in an event loop, because otherwise we might see a dead lock
            // while waiting for the channels to be closed.
            closeable.closeAsync();
        } else {
            closeable.close();
        }
    }

    HttpChannelPool pool(EventLoop eventLoop) {
        final HttpChannelPool pool = pools.get(eventLoop);
        if (pool != null) {
            return pool;
        }

        return pools.computeIfAbsent(eventLoop,
                                     e -> new HttpChannelPool(this, eventLoop,
                                                              sslCtxHttp1Or2, sslCtxHttp1Only,
                                                              connectionPoolListener()));
    }
}

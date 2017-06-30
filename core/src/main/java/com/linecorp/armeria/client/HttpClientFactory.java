/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.client.pool.KeyedChannelPoolHandler;
import com.linecorp.armeria.client.pool.PoolKey;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.ChannelUtil;
import com.linecorp.armeria.internal.TransportType;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.AddressResolverGroup;

/**
 * A {@link ClientFactory} that creates an HTTP client.
 */
final class HttpClientFactory extends AbstractClientFactory {

    private static final Set<Scheme> SUPPORTED_SCHEMES =
            Arrays.stream(SessionProtocol.values())
                  .map(p -> Scheme.of(SerializationFormat.NONE, p))
                  .collect(toImmutableSet());

    private final EventLoopGroup workerGroup;
    private final boolean shutdownWorkerGroupOnClose;
    private final Bootstrap baseBootstrap;
    private final Consumer<? super SslContextBuilder> sslContextCustomizer;
    private final long idleTimeoutMillis;
    private final boolean useHttp2Preface;
    private final boolean useHttp1Pipelining;
    private final ConnectionPoolListenerImpl connectionPoolListener;

    // FIXME(trustin): Reuse idle connections instead of creating a new connection for every event loop.
    //                 Currently, when a client makes an invocation from a non-I/O thread, it simply chooses
    //                 an event loop using eventLoopGroup.next(). This makes the client factory to create as
    //                 many connections as the number of event loops. We don't really do this when there's an
    //                 idle connection established already regardless of its event loop.
    private final Supplier<EventLoop> eventLoopSupplier =
            () -> RequestContext.mapCurrent(RequestContext::eventLoop, () -> eventLoopGroup().next());

    HttpClientFactory(
            EventLoopGroup workerGroup, boolean shutdownWorkerGroupOnClose,
            Map<ChannelOption<?>, Object> socketOptions,
            Consumer<? super SslContextBuilder> sslContextCustomizer,
            Function<? super EventLoopGroup,
                     ? extends AddressResolverGroup<? extends InetSocketAddress>> addressResolverGroupFactory,
            long idleTimeoutMillis, boolean useHttp2Preface, boolean useHttp1Pipelining,
            KeyedChannelPoolHandler<? super PoolKey> connectionPoolListener) {


        final Bootstrap baseBootstrap = new Bootstrap();
        baseBootstrap.channel(TransportType.socketChannelType(workerGroup));
        baseBootstrap.resolver(addressResolverGroupFactory.apply(workerGroup));

        socketOptions.forEach((option, value) -> {
            @SuppressWarnings("unchecked")
            final ChannelOption<Object> castOption = (ChannelOption<Object>) option;
            baseBootstrap.option(castOption, value);
        });

        this.workerGroup = workerGroup;
        this.shutdownWorkerGroupOnClose = shutdownWorkerGroupOnClose;
        this.baseBootstrap = baseBootstrap;
        this.sslContextCustomizer = sslContextCustomizer;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.useHttp2Preface = useHttp2Preface;
        this.useHttp1Pipelining = useHttp1Pipelining;
        this.connectionPoolListener = new ConnectionPoolListenerImpl(connectionPoolListener);
    }

    /**
     * Returns a new {@link Bootstrap} whose {@link ChannelFactory}, {@link AddressResolverGroup} and
     * socket options are pre-configured.
     */
    Bootstrap newBootstrap() {
        return baseBootstrap.clone();
    }

    Consumer<? super SslContextBuilder> sslContextCustomizer() {
        return sslContextCustomizer;
    }

    long idleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    boolean useHttp2Preface() {
        return useHttp2Preface;
    }

    boolean useHttp1Pipelining() {
        return useHttp1Pipelining;
    }

    KeyedChannelPoolHandler<? super PoolKey> connectionPoolListener() {
        return connectionPoolListener;
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
    public <T> T newClient(URI uri, Class<T> clientType, ClientOptions options) {
        final Scheme scheme = validateScheme(uri);

        validateClientType(clientType);

        final Client<HttpRequest, HttpResponse> delegate = options.decoration().decorate(
                HttpRequest.class, HttpResponse.class, new HttpClientDelegate(this));

        if (clientType == Client.class) {
            @SuppressWarnings("unchecked")
            final T castClient = (T) delegate;
            return castClient;
        }

        final Endpoint endpoint = newEndpoint(uri);

        if (clientType == HttpClient.class) {
            final HttpClient client = newHttpClient(uri, scheme, endpoint, options, delegate);

            @SuppressWarnings("unchecked")
            T castClient = (T) client;
            return castClient;
        } else {
            throw new IllegalArgumentException("unsupported client type: " + clientType.getName());
        }
    }

    @Override
    public <T> Optional<ClientBuilderParams> clientBuilderParams(T client) {
        return Optional.empty();
    }

    private DefaultHttpClient newHttpClient(URI uri, Scheme scheme, Endpoint endpoint, ClientOptions options,
                                            Client<HttpRequest, HttpResponse> delegate) {
        return new DefaultHttpClient(
                new DefaultClientBuilderParams(this, uri, HttpClient.class, options),
                delegate, scheme.sessionProtocol(), endpoint);
    }

    private static void validateClientType(Class<?> clientType) {
        if (clientType != HttpClient.class && clientType != Client.class) {
            throw new IllegalArgumentException(
                    "clientType: " + clientType +
                    " (expected: " + HttpClient.class.getSimpleName() + " or " +
                    Client.class.getSimpleName() + ')');
        }
    }

    @Override
    public void close() {
        connectionPoolListener.close().join();
        if (shutdownWorkerGroupOnClose) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static final class ConnectionPoolListenerImpl implements KeyedChannelPoolHandler<PoolKey> {

        private final KeyedChannelPoolHandler<? super PoolKey> connectionPoolListener;
        private final Set<Channel> channels = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private volatile boolean closed;

        ConnectionPoolListenerImpl(KeyedChannelPoolHandler<? super PoolKey> connectionPoolListener) {
            this.connectionPoolListener = connectionPoolListener;
        }

        @Override
        public void channelCreated(PoolKey key, Channel ch) throws Exception {
            if (closed) {
                ch.close();
                return;
            }

            channels.add(ch);
            connectionPoolListener.channelCreated(key, ch);
        }

        @Override
        public void channelAcquired(PoolKey key, Channel ch) throws Exception {
            connectionPoolListener.channelAcquired(key, ch);
        }

        @Override
        public void channelReleased(PoolKey key, Channel ch) throws Exception {
            connectionPoolListener.channelReleased(key, ch);
        }

        @Override
        public void channelClosed(PoolKey key, Channel ch) throws Exception {
            channels.remove(ch);
            connectionPoolListener.channelClosed(key, ch);
        }

        CompletableFuture<Void> close() {
            closed = true;
            return ChannelUtil.close(channels);
        }
    }
}

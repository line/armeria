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

package com.linecorp.armeria.client.http;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import java.net.URI;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Predicate;

import com.google.common.collect.MapMaker;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.DefaultClientBuilderParams;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.NonDecoratingClientFactory;
import com.linecorp.armeria.client.SessionOptions;
import com.linecorp.armeria.client.pool.DefaultKeyedChannelPool;
import com.linecorp.armeria.client.pool.KeyedChannelPool;
import com.linecorp.armeria.client.pool.KeyedChannelPoolHandler;
import com.linecorp.armeria.client.pool.KeyedChannelPoolHandlerAdapter;
import com.linecorp.armeria.client.pool.PoolKey;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.http.HttpSessionProtocols;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;

/**
 * A {@link ClientFactory} that creates an HTTP client.
 */
public class HttpClientFactory extends NonDecoratingClientFactory {

    private static final Set<Scheme> SUPPORTED_SCHEMES =
            HttpSessionProtocols.values().stream()
                                .map(p -> Scheme.of(SerializationFormat.NONE, p))
                                .collect(toImmutableSet());

    private static final KeyedChannelPoolHandlerAdapter<PoolKey> NOOP_POOL_HANDLER =
            new KeyedChannelPoolHandlerAdapter<>();

    private static final Predicate<Channel> POOL_HEALTH_CHECKER =
            ch -> ch.isActive() && HttpSession.get(ch).isActive();

    private final ConcurrentMap<EventLoop, KeyedChannelPool<PoolKey>> pools = new MapMaker().weakKeys()
                                                                                            .makeMap();
    private final HttpClientDelegate clientDelegate;

    /**
     * Creates a new instance with the default {@link SessionOptions}.
     */
    public HttpClientFactory() {
        this(false);
    }

    /**
     * Creates a new instance with the default {@link SessionOptions}.
     *
     * @param useDaemonThreads whether to create I/O event loop threads as daemon threads
     */
    public HttpClientFactory(boolean useDaemonThreads) {
        this(SessionOptions.DEFAULT, useDaemonThreads);
    }

    /**
     * Creates a new instance with the specified {@link SessionOptions}.
     */
    public HttpClientFactory(SessionOptions options) {
        this(options, false);
    }

    /**
     * Creates a new instance with the specified {@link SessionOptions}.
     *
     * @param useDaemonThreads whether to create I/O event loop threads as daemon threads
     */
    public HttpClientFactory(SessionOptions options, boolean useDaemonThreads) {
        super(options, useDaemonThreads);
        clientDelegate = new HttpClientDelegate(this);
    }

    @Override
    public Set<Scheme> supportedSchemes() {
        return SUPPORTED_SCHEMES;
    }

    @Override
    public <T> T newClient(URI uri, Class<T> clientType, ClientOptions options) {
        final Scheme scheme = validateScheme(uri);

        validateClientType(clientType);

        final Client<HttpRequest, HttpResponse> delegate = options.decoration().decorate(
                HttpRequest.class, HttpResponse.class, clientDelegate);

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
        for (Iterator<KeyedChannelPool<PoolKey>> i = pools.values().iterator(); i.hasNext();) {
            i.next().close();
            i.remove();
        }
        super.close();
    }

    KeyedChannelPool<PoolKey> pool(EventLoop eventLoop) {
        KeyedChannelPool<PoolKey> pool = pools.get(eventLoop);
        if (pool != null) {
            return pool;
        }

        return pools.computeIfAbsent(eventLoop, e -> {
            final Bootstrap bootstrap = newBootstrap();
            final SessionOptions options = options();

            bootstrap.group(eventLoop);

            Function<PoolKey, Future<Channel>> channelFactory =
                    new HttpSessionChannelFactory(bootstrap, options);

            final KeyedChannelPoolHandler<PoolKey> handler =
                    options.poolHandlerDecorator().apply(NOOP_POOL_HANDLER);

            return new DefaultKeyedChannelPool<>(
                    eventLoop, channelFactory, POOL_HEALTH_CHECKER, handler, true);
        });
    }
}

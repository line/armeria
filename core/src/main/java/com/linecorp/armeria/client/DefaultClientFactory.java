/*
 * Copyright 2017 LINE Corporation
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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.ReleasableHolder;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolverGroup;

/**
 * A {@link ClientFactory} which combines all discovered {@link ClientFactory} implementations.
 *
 * <h3>How are the {@link ClientFactory}s discovered?</h3>
 *
 * <p>{@link DefaultClientFactory} looks up the {@link ClientFactoryProvider}s available in the current JVM
 * using Java SPI (Service Provider Interface). The {@link ClientFactoryProvider} implementations will create
 * the {@link ClientFactory} implementations.
 */
final class DefaultClientFactory implements ClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(DefaultClientFactory.class);

    private static volatile boolean shutdownHookDisabled;

    static final DefaultClientFactory DEFAULT =
            (DefaultClientFactory) ClientFactory.builder().build();

    static final DefaultClientFactory INSECURE =
            (DefaultClientFactory) ClientFactory.builder().tlsNoVerify().build();

    static {
        if (DefaultClientFactory.class.getClassLoader() == ClassLoader.getSystemClassLoader()) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (!shutdownHookDisabled) {
                    ClientFactory.closeDefault();
                }
            }));
        }
    }

    static void disableShutdownHook0() {
        shutdownHookDisabled = true;
    }

    private final HttpClientFactory httpClientFactory;
    private final Map<Scheme, ClientFactory> clientFactories;
    private final List<ClientFactory> clientFactoriesToClose;

    DefaultClientFactory(HttpClientFactory httpClientFactory) {
        this.httpClientFactory = httpClientFactory;

        final List<ClientFactory> availableClientFactories = new ArrayList<>();
        availableClientFactories.add(httpClientFactory);

        Streams.stream(ServiceLoader.load(ClientFactoryProvider.class,
                                          DefaultClientFactory.class.getClassLoader()))
               .map(provider -> provider.newFactory(httpClientFactory))
               .forEach(availableClientFactories::add);

        final ImmutableMap.Builder<Scheme, ClientFactory> builder = ImmutableMap.builder();
        for (ClientFactory f : availableClientFactories) {
            f.supportedSchemes().forEach(s -> builder.put(s, f));
        }

        clientFactories = builder.build();
        clientFactoriesToClose = ImmutableList.copyOf(availableClientFactories).reverse();
    }

    @Override
    public Set<Scheme> supportedSchemes() {
        return clientFactories.keySet();
    }

    @Override
    public EventLoopGroup eventLoopGroup() {
        return httpClientFactory.eventLoopGroup();
    }

    @Override
    public Supplier<EventLoop> eventLoopSupplier() {
        return httpClientFactory.eventLoopSupplier();
    }

    @Override
    public ReleasableHolder<EventLoop> acquireEventLoop(Endpoint endpoint, SessionProtocol sessionProtocol) {
        return httpClientFactory.acquireEventLoop(endpoint, sessionProtocol);
    }

    @Override
    public MeterRegistry meterRegistry() {
        return httpClientFactory.meterRegistry();
    }

    @Override
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        httpClientFactory.setMeterRegistry(meterRegistry);
    }

    @Override
    public ClientFactoryOptions options() {
        return httpClientFactory.options();
    }

    @Override
    public Object newClient(ClientBuilderParams params) {
        validateParams(params);
        final Scheme scheme = params.scheme();
        // `factory` must be non-null because we validates params.scheme() with validateParams().
        final ClientFactory factory = clientFactories.get(scheme);
        assert factory != null;
        return factory.newClient(params);
    }

    @Override
    public <T> T unwrap(Object client, Class<T> type) {
        final T params = ClientFactory.super.unwrap(client, type);
        if (params != null) {
            return params;
        }

        for (ClientFactory factory : clientFactories.values()) {
            final T p = factory.unwrap(client, type);
            if (p != null) {
                return p;
            }
        }

        return null;
    }

    @Override
    public void close() {
        // The global default should never be closed.
        if (this == ClientFactory.ofDefault()) {
            logger.debug("Refusing to close the default {}; must be closed via closeDefault()",
                         ClientFactory.class.getSimpleName());
            return;
        }

        doClose();
    }

    void doClose() {
        clientFactoriesToClose.forEach(ClientFactory::close);
    }

    @VisibleForTesting
    AddressResolverGroup<InetSocketAddress> addressResolverGroup() {
        return httpClientFactory.addressResolverGroup();
    }
}

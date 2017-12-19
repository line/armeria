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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.util.ReleasableHolder;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

/**
 * A {@link ClientFactory} which combines all discovered {@link ClientFactory} implementations.
 *
 * <h3>How are the {@link ClientFactory}s discovered?</h3>
 *
 * <p>{@link DefaultClientFactory} looks up the {@link ClientFactoryProvider}s available in the current JVM
 * using Java SPI (Service Provider Interface). The {@link ClientFactoryProvider} implementations will create
 * the {@link ClientFactory} implementations.
 */
final class DefaultClientFactory extends AbstractClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(DefaultClientFactory.class);

    static {
        if (DefaultClientFactory.class.getClassLoader() == ClassLoader.getSystemClassLoader()) {
            Runtime.getRuntime().addShutdownHook(new Thread(ClientFactory::closeDefault));
        }
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
    public ReleasableHolder<EventLoop> acquireEventLoop(Endpoint endpoint) {
        return httpClientFactory.acquireEventLoop(endpoint);
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
    public <T> T newClient(URI uri, Class<T> clientType, ClientOptions options) {
        final Scheme scheme = validateScheme(uri);
        return clientFactories.get(scheme).newClient(uri, clientType, options);
    }

    @Override
    public <T> Optional<ClientBuilderParams> clientBuilderParams(T client) {
        for (ClientFactory factory : clientFactories.values()) {
            Optional<ClientBuilderParams> params = factory.clientBuilderParams(client);
            if (params.isPresent()) {
                return params;
            }
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        // The global default should never be closed.
        if (this == ClientFactory.DEFAULT) {
            logger.debug("Refusing to close the default {}; must be closed via closeDefault()",
                         ClientFactory.class.getSimpleName());
            return;
        }

        doClose();
    }

    void doClose() {
        clientFactoriesToClose.forEach(ClientFactory::close);
    }
}

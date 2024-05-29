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

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Streams;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;
import com.linecorp.armeria.common.util.ReleasableHolder;
import com.linecorp.armeria.common.util.ShutdownHooks;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;
import io.netty.util.ResourceLeakTracker;

/**
 * A {@link ClientFactory} which combines all discovered {@link ClientFactory} implementations.
 *
 * <h2>How are the {@link ClientFactory}s discovered?</h2>
 *
 * <p>{@link DefaultClientFactory} looks up the {@link ClientFactoryProvider}s available in the current JVM
 * using Java SPI (Service Provider Interface). The {@link ClientFactoryProvider} implementations will create
 * the {@link ClientFactory} implementations.
 */
final class DefaultClientFactory implements ClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(DefaultClientFactory.class);

    private static final ResourceLeakDetector<ClientFactory> leakDetector =
            ResourceLeakDetectorFactory.instance().newResourceLeakDetector(ClientFactory.class);

    private static volatile boolean shutdownHookDisabled;

    static final DefaultClientFactory DEFAULT =
            (DefaultClientFactory) ClientFactory.builder().build();

    static final DefaultClientFactory INSECURE =
            (DefaultClientFactory) ClientFactory.builder().tlsNoVerify().build();

    static {
        if (DefaultClientFactory.class.getClassLoader() == ClassLoader.getSystemClassLoader()) {
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (!shutdownHookDisabled) {
                        ClientFactory.closeDefault();
                    }
                }));
            } catch (IllegalStateException e) {
                logger.debug("Skipped adding a shutdown hook to the DefaultClientFactory.", e);
            }
        }
    }

    static void disableShutdownHook0() {
        shutdownHookDisabled = true;
    }

    private final HttpClientFactory httpClientFactory;
    private final Multimap<Scheme, ClientFactory> clientFactories;
    private final List<ClientFactory> clientFactoriesToClose;
    private final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(this::closeAsync);
    @Nullable
    private final ResourceLeakTracker<ClientFactory> leakTracker = leakDetector.track(this);

    DefaultClientFactory(HttpClientFactory httpClientFactory) {
        this.httpClientFactory = httpClientFactory;

        final List<ClientFactory> availableClientFactories = new ArrayList<>();

        // Give priority to custom client factories.
        Streams.stream(ServiceLoader.load(ClientFactoryProvider.class,
                                          DefaultClientFactory.class.getClassLoader()))
               .map(provider -> provider.newFactory(httpClientFactory))
               .forEach(availableClientFactories::add);

        availableClientFactories.add(httpClientFactory);

        final ImmutableListMultimap.Builder<Scheme, ClientFactory> builder = ImmutableListMultimap.builder();
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
    public ReleasableHolder<EventLoop> acquireEventLoop(SessionProtocol sessionProtocol,
                                                        EndpointGroup endpointGroup,
                                                        @Nullable Endpoint endpoint) {
        return httpClientFactory.acquireEventLoop(sessionProtocol, endpointGroup, endpoint);
    }

    @Override
    public MeterRegistry meterRegistry() {
        return httpClientFactory.meterRegistry();
    }

    @Override
    @Deprecated
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        httpClientFactory.setMeterRegistry(meterRegistry);
    }

    @Override
    public int numConnections() {
        return httpClientFactory.numConnections();
    }

    @Override
    public ClientFactoryOptions options() {
        return httpClientFactory.options();
    }

    @Override
    public Object newClient(ClientBuilderParams params) {
        if (isClosing()) {
            throw new IllegalStateException("Cannot create a client because the factory is closing.");
        }
        validateParams(params);
        final Scheme scheme = params.scheme();
        final Class<?> clientType = params.clientType();
        for (ClientFactory factory : clientFactories.get(scheme)) {
            if (factory.isClientTypeSupported(clientType)) {
                return factory.newClient(params);
            }
        }
        // Since we passed validation, there should have been at least 1 factory for this scheme,
        // but for some reason none of these passed the filter.
        throw new IllegalStateException(
                "No ClientFactory for scheme: " + scheme + " matched clientType: " + clientType);
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
    public ClientFactory unwrap() {
        return httpClientFactory;
    }

    @Nullable
    @Override
    public <T> T as(Class<T> type) {
        requireNonNull(type, "type");

        T result = ClientFactory.super.as(type);
        if (result != null) {
            return result;
        }

        for (ClientFactory f : clientFactories.values()) {
            result = f.as(type);
            if (result != null) {
                return result;
            }
        }

        return null;
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
        return closeAsync(true);
    }

    CompletableFuture<?> closeAsync(boolean checkDefault) {
        if (checkDefault && checkDefault()) {
            return whenClosed();
        }
        return closeable.closeAsync();
    }

    private void closeAsync(CompletableFuture<?> future) {
        if (leakTracker != null) {
            leakTracker.close(this);
        }

        final CompletableFuture<?>[] delegateCloseFutures =
                clientFactoriesToClose.stream()
                                      .map(ClientFactory::closeAsync)
                                      .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(delegateCloseFutures).handle((unused, cause) -> {
            if (cause != null) {
                future.completeExceptionally(cause);
            } else {
                future.complete(null);
            }
            return null;
        });
    }

    @Override
    public void close() {
        if (checkDefault()) {
            return;
        }
        closeable.close();
    }

    @Override
    public CompletableFuture<Void> closeOnJvmShutdown(Runnable whenClosing) {
        requireNonNull(whenClosing, "whenClosing");
        return ShutdownHooks.addClosingTask(this, whenClosing);
    }

    private boolean checkDefault() {
        // The global default should never be closed.
        if (this == DEFAULT || this == INSECURE) {
            logger.debug("Refusing to close the default {}; must be closed via closeDefault()",
                         ClientFactory.class.getSimpleName());
            return true;
        }
        return false;
    }

    @VisibleForTesting
    AddressResolverGroup<InetSocketAddress> addressResolverGroup() {
        return httpClientFactory.addressResolverGroup();
    }
}

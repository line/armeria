/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.armeria.common;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryOptions;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;
import com.linecorp.armeria.common.util.ReleasableHolder;
import com.linecorp.armeria.common.util.ShutdownHooks;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

public final class TestDefaultClientFactory implements ClientFactory {

    public static final String CLOSED_MARKER = "TestDefaultClientFactory closed";
    public static final String NEW_CLIENT_MARKER_PREFIX = "TestDefaultClientFactory newClient: ";

    private final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(future -> {
        System.out.println(CLOSED_MARKER);
        future.complete(null);
    });

    @Override
    public java.util.Set<Scheme> supportedSchemes() {
        return ImmutableSet.of(
                Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP),
                Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTPS));
    }

    @Override
    public EventLoopGroup eventLoopGroup() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Supplier<EventLoop> eventLoopSupplier() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ReleasableHolder<EventLoop> acquireEventLoop(SessionProtocol sessionProtocol,
                                                        EndpointGroup endpointGroup,
                                                        Endpoint endpoint) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MeterRegistry meterRegistry() {
        return Metrics.globalRegistry;
    }

    @Override
    public void setMeterRegistry(MeterRegistry meterRegistry) {}

    @Override
    public ClientFactoryOptions options() {
        return ClientFactoryOptions.of();
    }

    @Override
    public Object newClient(ClientBuilderParams params) {
        final Class<?> clientType = params.clientType();
        System.out.println(NEW_CLIENT_MARKER_PREFIX + clientType.getName());
        if (!clientType.isInterface()) {
            throw new UnsupportedOperationException("unsupported client type: " + clientType.getName());
        }

        return Proxy.newProxyInstance(
                clientType.getClassLoader(),
                new Class<?>[] { clientType },
                (proxy, method, args) -> {
                    final String methodName = method.getName();
                    if ("toString".equals(methodName)) {
                        return clientType.getSimpleName() + " proxy";
                    }
                    if ("hashCode".equals(methodName)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(methodName)) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException(methodName);
                });
    }

    @Override
    public int numConnections() {
        return 0;
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

    @Override
    public void close() {
        closeable.close();
    }

    @Override
    public CompletableFuture<Void> closeOnJvmShutdown(Runnable whenClosing) {
        return ShutdownHooks.addClosingTask(this, whenClosing);
    }
}

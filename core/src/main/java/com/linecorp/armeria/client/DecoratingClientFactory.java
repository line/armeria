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

import java.net.URI;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.AbstractUnwrappable;
import com.linecorp.armeria.common.util.ReleasableHolder;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

/**
 * A {@link ClientFactory} that delegates the creation of {@link Client}s to another {@link ClientFactory}.
 */
public class DecoratingClientFactory extends AbstractUnwrappable<ClientFactory> implements ClientFactory {

    /**
     * Creates a new instance.
     */
    protected DecoratingClientFactory(ClientFactory delegate) {
        super(delegate);
    }

    /**
     * Creates a new {@link HttpClient} which uses the same {@link SessionProtocol}, {@link EndpointGroup} and
     * {@link ClientOptions} with the specified {@link ClientBuilderParams}. Note that {@code path} and
     * {@link SerializationFormat} are always {@code "/"} and {@link SerializationFormat#NONE}.
     */
    protected final HttpClient newHttpClient(ClientBuilderParams params) {
        final URI uri = params.uri();
        final ClientBuilderParams newParams;
        final ClientOptions newOptions = params.options().toBuilder().factory(unwrap()).build();
        if (Clients.isUndefinedUri(uri)) {
            newParams = ClientBuilderParams.of(uri, HttpClient.class, newOptions);
        } else {
            final Scheme newScheme = Scheme.of(SerializationFormat.NONE, params.scheme().sessionProtocol());
            newParams = ClientBuilderParams.of(newScheme, params.endpointGroup(),
                                               null, HttpClient.class, newOptions);
        }

        return (HttpClient) unwrap().newClient(newParams);
    }

    @Override
    public Set<Scheme> supportedSchemes() {
        return unwrap().supportedSchemes();
    }

    @Override
    public EventLoopGroup eventLoopGroup() {
        return unwrap().eventLoopGroup();
    }

    @Override
    public Supplier<EventLoop> eventLoopSupplier() {
        return unwrap().eventLoopSupplier();
    }

    @Override
    public ReleasableHolder<EventLoop> acquireEventLoop(SessionProtocol sessionProtocol,
                                                        EndpointGroup endpointGroup,
                                                        @Nullable Endpoint endpoint) {
        return unwrap().acquireEventLoop(sessionProtocol, endpointGroup, endpoint);
    }

    @Override
    public MeterRegistry meterRegistry() {
        return unwrap().meterRegistry();
    }

    @Override
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        unwrap().setMeterRegistry(meterRegistry);
    }

    @Override
    public ClientFactoryOptions options() {
        return unwrap().options();
    }

    @Override
    public Object newClient(ClientBuilderParams params) {
        return unwrap().newClient(params);
    }

    @Override
    public <T> ClientBuilderParams clientBuilderParams(T client) {
        return unwrap().clientBuilderParams(client);
    }

    @Override
    public <T> T unwrap(Object client, Class<T> type) {
        return unwrap().unwrap(client, type);
    }

    @Override
    public boolean isClosing() {
        return unwrap().isClosing();
    }

    @Override
    public boolean isClosed() {
        return unwrap().isClosed();
    }

    @Override
    public CompletableFuture<?> whenClosed() {
        return unwrap().whenClosed();
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return unwrap().closeAsync();
    }

    @Override
    public void close() {
        unwrap().close();
    }

    @Override
    public int numConnections() {
        return unwrap().numConnections();
    }
}

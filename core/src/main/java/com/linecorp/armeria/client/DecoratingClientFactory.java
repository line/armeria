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

import static com.linecorp.armeria.client.WebClientBuilder.isUndefinedUri;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.Set;
import java.util.function.Supplier;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.ReleasableHolder;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

/**
 * A {@link ClientFactory} that delegates the creation of {@link Client}s to another {@link ClientFactory}.
 */
public class DecoratingClientFactory implements ClientFactory {

    private final ClientFactory delegate;

    /**
     * Creates a new instance.
     */
    protected DecoratingClientFactory(ClientFactory delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the delegate {@link ClientFactory}.
     */
    protected final ClientFactory delegate() {
        return delegate;
    }

    /**
     * Creates a new {@link HttpClient} which uses the same {@link SessionProtocol}, {@link EndpointGroup} and
     * {@link ClientOptions} with the specified {@link ClientBuilderParams}. Note that {@code path} and
     * {@link SerializationFormat} are always {@code "/"} and {@link SerializationFormat#NONE}.
     */
    protected final HttpClient newHttpClientDelegate(ClientBuilderParams params) {
        final URI uri = params.uri();
        if (isUndefinedUri(uri)) {
            return (HttpClient) delegate().newClient(
                    ClientBuilderParams.of(delegate(), uri, HttpClient.class, params.options()));
        }

        final Scheme newScheme = Scheme.of(SerializationFormat.NONE, params.scheme().sessionProtocol());
        return (HttpClient) delegate().newClient(
                ClientBuilderParams.of(delegate(), newScheme, params.endpointGroup(),
                                       null, HttpClient.class, params.options()));
    }

    @Override
    public Set<Scheme> supportedSchemes() {
        return delegate().supportedSchemes();
    }

    @Override
    public EventLoopGroup eventLoopGroup() {
        return delegate().eventLoopGroup();
    }

    @Override
    public Supplier<EventLoop> eventLoopSupplier() {
        return delegate().eventLoopSupplier();
    }

    @Override
    public ReleasableHolder<EventLoop> acquireEventLoop(Endpoint endpoint, SessionProtocol sessionProtocol) {
        return delegate().acquireEventLoop(endpoint, sessionProtocol);
    }

    @Override
    public MeterRegistry meterRegistry() {
        return delegate().meterRegistry();
    }

    @Override
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        delegate().setMeterRegistry(meterRegistry);
    }

    @Override
    public ClientFactoryOptions options() {
        return delegate().options();
    }

    @Override
    public Object newClient(ClientBuilderParams params) {
        return delegate().newClient(params);
    }

    @Override
    public <T> ClientBuilderParams clientBuilderParams(T client) {
        return delegate().clientBuilderParams(client);
    }

    @Override
    public <T> T unwrap(Object client, Class<T> type) {
        return delegate().unwrap(client, type);
    }

    @Override
    public void close() {
        delegate().close();
    }
}

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

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.util.ReleasableHolder;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

/**
 * A {@link ClientFactory} that delegates the creation of {@link Client}s to another {@link ClientFactory}.
 */
public class DecoratingClientFactory extends AbstractClientFactory {

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
    protected ClientFactory delegate() {
        return delegate;
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
    public ReleasableHolder<EventLoop> acquireEventLoop(Endpoint endpoint) {
        return delegate().acquireEventLoop(endpoint);
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
    public <T> T newClient(URI uri, Class<T> clientType, ClientOptions options) {
        return delegate().newClient(uri, clientType, options);
    }

    @Override
    public <T> Optional<ClientBuilderParams> clientBuilderParams(T client) {
        return delegate().clientBuilderParams(client);
    }

    @Override
    public void close() {
        delegate().close();
    }
}

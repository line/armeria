/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.client.consul;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.internal.consul.ConsulClient;

import io.netty.channel.EventLoop;

/**
 * A Consul-based {@link EndpointGroup} implementation that retrieves the list of {@link Endpoint}s
 * from Consul using <a href="https://www.consul.io/api">Consul's HTTP API</a> and updates the
 * {@link Endpoint}s periodically.
 */
public final class ConsulEndpointGroup extends DynamicEndpointGroup {

    private static final Logger logger = LoggerFactory.getLogger(ConsulEndpointGroup.class);

    /**
     * Returns a {@link ConsulEndpointGroup} with the specified {@code serviceName}.
     * The returned {@link ConsulEndpointGroup} will retrieve the list of {@link Endpoint}s from
     * a local Consul agent at the default Consul service port.
     *
     * @param consulUri the URI of Consul API service
     * @param serviceName the service name to register
     */
    public static ConsulEndpointGroup of(URI consulUri, String serviceName) {
        return builder(consulUri, serviceName).build();
    }

    /**
     * Returns a newly-created {@link ConsulEndpointGroupBuilder} with the specified {@code consulUri}
     * and {@code serviceName} to build {@link ConsulEndpointGroupBuilder}.
     *
     * @param consulUri the URI of Consul API service
     * @param serviceName the service name to register
     */
    public static ConsulEndpointGroupBuilder builder(URI consulUri, String serviceName) {
        return new ConsulEndpointGroupBuilder(consulUri, serviceName);
    }

    private final ConsulClient consulClient;
    private final String serviceName;
    private final long registryFetchIntervalMillis;
    private final boolean useHealthyEndpoints;

    @Nullable
    private volatile ScheduledFuture<?> scheduledFuture;

    ConsulEndpointGroup(EndpointSelectionStrategy selectionStrategy, ConsulClient consulClient,
                        String serviceName, long registryFetchIntervalMillis, boolean useHealthyEndpoints) {
        super(selectionStrategy);
        this.consulClient = requireNonNull(consulClient, "consulClient");
        this.serviceName = requireNonNull(serviceName, "serviceName");
        this.registryFetchIntervalMillis = registryFetchIntervalMillis;
        this.useHealthyEndpoints = useHealthyEndpoints;

        update();
    }

    private void update() {
        if (isClosing()) {
            return;
        }

        final CompletableFuture<List<Endpoint>> response;
        final EventLoop eventLoop;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            if (useHealthyEndpoints) {
                response = consulClient.healthyEndpoints(serviceName);
            } else {
                response = consulClient.endpoints(serviceName);
            }
            eventLoop = captor.get().eventLoop().withoutContext();
        }

        response.handle((endpoints, cause) -> {
            if (isClosing()) {
                return null;
            }
            if (cause != null) {
                logger.warn("Unexpected exception while fetching the registry from: {}" +
                            " (serviceName: {})", consulClient.uri(), serviceName, cause);
            } else if (endpoints != null) {
                setEndpoints(endpoints);
            }

            scheduledFuture = eventLoop.schedule(this::update, registryFetchIntervalMillis,
                                                 TimeUnit.MILLISECONDS);
            return null;
        });
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        final ScheduledFuture<?> scheduledFuture = this.scheduledFuture;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
        future.complete(null);
    }
}

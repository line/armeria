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
import com.linecorp.armeria.internal.consul.ConsulClient;

import io.netty.channel.EventLoop;

/**
 * A Consul-based {@link EndpointGroup} implementation that retrieves the list of {@link Endpoint}s
 * from Consul using <a href="https://www.consul.io/api">Consul's RESTful HTTP API</a> and
 * updates the {@link Endpoint}s periodically.
 */
public final class ConsulEndpointGroup extends DynamicEndpointGroup {

    private static final Logger logger = LoggerFactory.getLogger(ConsulEndpointGroup.class);

    /**
     * Returns a {@link ConsulEndpointGroup} with the specified {@code serviceName}.
     * The returned {@link ConsulEndpointGroup} will retrieve the list of {@link Endpoint}s from
     * a local Consul agent(using default Consul service port).
     */
    public static ConsulEndpointGroup of(String serviceName) {
        return builder(serviceName).build();
    }

    /**
     * Returns a newly-created {@link ConsulEndpointGroupBuilder} with the specified {@code serviceName}.
     */
    public static ConsulEndpointGroupBuilder builder(String serviceName) {
        return new ConsulEndpointGroupBuilder(serviceName);
    }

    private final ConsulClient consulClient;
    private final String serviceName;
    private final long intervalMillis;
    private final boolean useHealthyEndpoints;

    @Nullable
    private volatile ScheduledFuture<?> scheduledFuture;

    /**
     * Creates a Consul-based {@link EndpointGroup}, endpoints will be retrieved by service name using
     * {@code ConsulClient}.
     * @param consulClient the Consul client
     * @param serviceName the service name to retrieve
     * @param intervalMillis the health check interval on milliseconds to check
     * @param useHealthyEndpoints whether to use healthy endpoints
     */
    ConsulEndpointGroup(ConsulClient consulClient, String serviceName, long intervalMillis,
                        boolean useHealthyEndpoints) {
        this.consulClient = consulClient;
        this.serviceName = serviceName;
        this.intervalMillis = intervalMillis;
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
            if (cause != null) {
                logger.warn("Unexpected exception while fetching the registry from: {}." +
                            " (serviceName: {})", consulClient.uri(), serviceName, cause);
            } else if (endpoints != null) {
                setEndpoints(endpoints);
            }

            scheduledFuture = eventLoop.schedule(this::update,
                                                 intervalMillis,
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

/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.client.nacos;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.nacos.NacosClient;

import io.netty.channel.EventLoop;

/**
 * A Nacos-based {@link EndpointGroup} implementation that retrieves the list of {@link Endpoint} from Nacos
 * using <a href="https://nacos.io/en-us/docs/v2/guide/user/open-api.html">Nacos's HTTP Open API</a>
 * and updates the {@link Endpoint}s periodically.
 */
public class NacosEndpointGroup extends DynamicEndpointGroup {
    private static final Logger logger = LoggerFactory.getLogger(NacosEndpointGroup.class);

    /**
     * Returns a {@link NacosEndpointGroup} with the specified {@code serviceName}.
     *
     * @param nacosUri the URI of Nacos API service
     * @param serviceName the service name to register
     */
    public static NacosEndpointGroup of(URI nacosUri, String serviceName) {
        return builder(nacosUri, serviceName).build();
    }

    /**
     * Returns a newly-created {@link NacosEndpointGroupBuilder} with the specified {@code nacosUri}
     * and {@code serviceName} to build {@link NacosEndpointGroupBuilder}.
     *
     * @param nacosUri the URI of Nacos API service
     * @param serviceName the service name to register
     */
    public static NacosEndpointGroupBuilder builder(URI nacosUri, String serviceName) {
        return new NacosEndpointGroupBuilder(nacosUri, serviceName);
    }

    private final NacosClient nacosClient;

    private final String serviceName;

    private final long registryFetchIntervalMillis;

    @Nullable
    private final String namespaceId;

    @Nullable
    private final String groupName;

    @Nullable
    private final String clusterName;

    @Nullable
    private final String app;

    private final boolean useHealthyEndpoints;

    @Nullable
    private volatile ScheduledFuture<?> scheduledFuture;

    NacosEndpointGroup(EndpointSelectionStrategy selectionStrategy, boolean allowEmptyEndpoints,
                       long selectionTimeoutMillis, NacosClient nacosClient, String serviceName,
                       long registryFetchIntervalMillis, @Nullable String namespaceId,
                       @Nullable String groupName, @Nullable String clusterName,
                       @Nullable String app, boolean useHealthyEndpoints) {
        super(selectionStrategy, allowEmptyEndpoints, selectionTimeoutMillis);
        this.nacosClient = requireNonNull(nacosClient, "nacosClient");
        this.serviceName = requireNonNull(serviceName, "serviceName");
        this.registryFetchIntervalMillis = registryFetchIntervalMillis;
        this.namespaceId = namespaceId;
        this.groupName = groupName;
        this.clusterName = clusterName;
        this.app = app;
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
            response = nacosClient.endpoints(serviceName, namespaceId, groupName, clusterName,
                                             useHealthyEndpoints, app);
            eventLoop = captor.get().eventLoop().withoutContext();
        }

        response.handle((endpoints, cause) -> {
            if (isClosing()) {
                return null;
            }
            if (cause != null) {
                logger.warn("Unexpected exception while fetching the registry from: {}" +
                        " (serviceName: {})", nacosClient.uri(), serviceName, cause);
            } else {
                setEndpoints(endpoints);
            }

            scheduledFuture = eventLoop.schedule(this::update,
                    registryFetchIntervalMillis, TimeUnit.MILLISECONDS);
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

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .omitNullValues()
                .add("serviceName", serviceName)
                .add("registryFetchIntervalMillis", registryFetchIntervalMillis)
                .add("namespaceId", namespaceId)
                .add("groupName", groupName)
                .add("clusterName", clusterName)
                .add("app", app)
                .add("useHealthyEndpoints", useHealthyEndpoints)
                .add("clusterName", clusterName)
                .add("serviceName", serviceName)
                .toString();
    }
}

/*
 * Copyright 2024 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.nacos.NacosClient;

import io.netty.util.concurrent.EventExecutor;

/**
 * A Nacos-based {@link EndpointGroup} implementation that retrieves the list of {@link Endpoint} from Nacos
 * using <a href="https://nacos.io/en-us/docs/v2/guide/user/open-api.html">Nacos's HTTP Open API</a>
 * and updates the {@link Endpoint}s periodically.
 */
@UnstableApi
public final class NacosEndpointGroup extends DynamicEndpointGroup {

    private static final Logger logger = LoggerFactory.getLogger(NacosEndpointGroup.class);

    /**
     * Returns a {@link NacosEndpointGroup} with the specified {@code serviceName}.
     */
    public static NacosEndpointGroup of(URI nacosUri, String serviceName) {
        return builder(nacosUri, serviceName).build();
    }

    /**
     * Returns a newly-created {@link NacosEndpointGroupBuilder} with the specified {@code nacosUri}
     * and {@code serviceName} to build {@link NacosEndpointGroupBuilder}.
     *
     * @param nacosUri the URI of Nacos API service, including the path up to but not including API version.
     *                 (example: http://localhost:8848/nacos)
     */
    public static NacosEndpointGroupBuilder builder(URI nacosUri, String serviceName) {
        return new NacosEndpointGroupBuilder(nacosUri, serviceName);
    }

    private final NacosClient nacosClient;

    private final long registryFetchIntervalMillis;

    private final EventExecutor eventLoop;

    @Nullable
    private ScheduledFuture<?> scheduledFuture;

    NacosEndpointGroup(EndpointSelectionStrategy selectionStrategy, boolean allowEmptyEndpoints,
                       long selectionTimeoutMillis, NacosClient nacosClient,
                       long registryFetchIntervalMillis) {
        super(selectionStrategy, allowEmptyEndpoints, selectionTimeoutMillis);
        this.nacosClient = requireNonNull(nacosClient, "nacosClient");
        this.registryFetchIntervalMillis = registryFetchIntervalMillis;
        eventLoop = CommonPools.workerGroup().next();

        update();
    }

    private void update() {
        if (isClosing()) {
            return;
        }

        nacosClient.endpoints()
                   .handleAsync((endpoints, cause) -> {
                       if (isClosing()) {
                           return null;
                       }

                       if (cause != null) {
                           logger.warn("Unexpected exception while fetching the registry from: {}",
                                       nacosClient.uri(), cause);
                       } else {
                           setEndpoints(endpoints);
                       }

                       scheduledFuture = eventLoop.schedule(this::update, registryFetchIntervalMillis,
                                                            TimeUnit.MILLISECONDS);
                       return null;
                   }, eventLoop);
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> doCloseAsync(future));
            return;
        }

        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }

        future.complete(null);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("registryFetchIntervalMillis", registryFetchIntervalMillis)
                          .toString();
    }
}

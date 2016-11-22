/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.routing.endpoint.healthcheck;

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;

import com.google.common.collect.ImmutableList;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.routing.EndpointGroup;

import jp.skypencil.guava.stream.GuavaCollectors;

/**
 * An {@link EndpointGroup} decorator that only provides healthy {@link Endpoint}s.
 */
public abstract class HealthCheckedEndpointGroup implements EndpointGroup {
    protected static final long DEFAULT_HEALTHCHECK_RETRY_INTERVAL_MILLIS = 3_000; // 3 seconds.

    private final ClientFactory clientFactory;
    private final EndpointGroup delegate;
    private final long healthCheckRetryIntervalMillis;

    volatile List<ServerConnection> allServers = ImmutableList.of();
    volatile List<ServerConnection> healthyServers = ImmutableList.of();

    /**
     * Creates a new instance.
     * A subclass being initialized with this constructor must call {@link #init()} before start being used.
     */
    protected HealthCheckedEndpointGroup(ClientFactory clientFactory,
                                         EndpointGroup delegate,
                                         long healthCheckRetryIntervalMillis) {
        this.clientFactory = requireNonNull(clientFactory, "clientFactory");
        this.delegate = requireNonNull(delegate, "delegate");
        this.healthCheckRetryIntervalMillis = healthCheckRetryIntervalMillis;
    }

    /**
     * Update healty servers and start to schedule healthcheck.
     * A subclass being initialized with this constructor must call {@link #init()} before start being used.
     */
    protected void init() {
        checkAndUpdateHealthyServers().join();
        scheduleCheckAndUpdateHealthyServers();
    }

    protected final ClientFactory clientFactory() {
        return clientFactory;
    }

    private void scheduleCheckAndUpdateHealthyServers() {
        clientFactory.eventLoopGroup().schedule(() -> {
            checkAndUpdateHealthyServers().thenRun(this::scheduleCheckAndUpdateHealthyServers);
        }, healthCheckRetryIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<Void> checkAndUpdateHealthyServers() {
        List<ServerConnection> checkedServers = updateServerList();

        CompletableFuture<List<Boolean>> healthCheckResults = CompletableFutures.successfulAsList(
                checkedServers.stream()
                              .map(connection -> connection.healthChecker.isHealthy(connection.endpoint()))
                              .collect(GuavaCollectors.toImmutableList()),
                t -> false);
        return healthCheckResults.handle(voidFunction((result, thrown) -> {
            healthyServers = IntStream
                    .range(0, result.size())
                    .filter(i -> result.get(i) != null && result.get(i))
                    .mapToObj(checkedServers::get)
                    .collect(GuavaCollectors.toImmutableList());
        }));
    }

    /**
     * Update the servers this health checker client talks to.
     */
    private List<ServerConnection> updateServerList() {
        Map<Endpoint, ServerConnection> allServersByEndpoint = allServers
                .stream()
                .collect(GuavaCollectors.toImmutableMap(ServerConnection::endpoint,
                                                        Function.identity()));
        return allServers = delegate
                .endpoints()
                .stream()
                .map(endpoint -> {
                    ServerConnection connection = allServersByEndpoint.get(endpoint);
                    if (connection != null) {
                        return connection;
                    }
                    return new ServerConnection(endpoint, createEndpointHealthChecker(endpoint));
                })
                .collect(GuavaCollectors.toImmutableList());
    }

    protected abstract EndpointHealthChecker createEndpointHealthChecker(Endpoint endpoint);

    @Override
    public List<Endpoint> endpoints() {
        return healthyServers.stream()
                             .map(ServerConnection::endpoint)
                             .collect(GuavaCollectors.toImmutableList());
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("HealthCheckedEndpointGroup(all:[");
        for (ServerConnection connection : allServers) {
            buf.append(connection.endpoint).append(',');
        }
        buf.setCharAt(buf.length() - 1, ']');
        buf.append(", healthy:[");
        for (ServerConnection connection : healthyServers) {
            buf.append(connection.endpoint).append(',');
        }
        buf.setCharAt(buf.length() - 1, ']');
        buf.append(')');
        return buf.toString();
    }

    /**
     * Returns whether an {@link Endpoint} is healthy or not.
     */
    @FunctionalInterface
    public interface EndpointHealthChecker {
        CompletableFuture<Boolean> isHealthy(Endpoint endpoint);
    }

    private static final class ServerConnection {
        private final Endpoint endpoint;
        private final EndpointHealthChecker healthChecker;

        private ServerConnection(Endpoint endpoint, EndpointHealthChecker healthChecker) {
            this.endpoint = endpoint;
            this.healthChecker = healthChecker;
        }

        Endpoint endpoint() {
            return endpoint;
        }
    }
}

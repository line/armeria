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
package com.linecorp.armeria.client.endpoint.healthcheck;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * An {@link EndpointGroup} decorator that only provides healthy {@link Endpoint}s.
 */
public abstract class HealthCheckedEndpointGroup extends DynamicEndpointGroup {
    protected static final Duration DEFAULT_HEALTHCHECK_RETRY_INTERVAL = Duration.ofSeconds(3);

    private final ClientFactory clientFactory;
    private final EndpointGroup delegate;
    private final Duration healthCheckRetryInterval;

    volatile List<ServerConnection> allServers = ImmutableList.of();

    /**
     * Creates a new instance.
     * A subclass being initialized with this constructor must call {@link #init()} before start being used.
     */
    protected HealthCheckedEndpointGroup(ClientFactory clientFactory,
                                         EndpointGroup delegate,
                                         Duration healthCheckRetryInterval) {
        this.clientFactory = requireNonNull(clientFactory, "clientFactory");
        this.delegate = requireNonNull(delegate, "delegate");
        this.healthCheckRetryInterval = requireNonNull(healthCheckRetryInterval, "healthCheckRetryInterval");
    }

    /**
     * Update healthy servers and start to schedule healthcheck.
     * A subclass being initialized with this constructor must call {@link #init()} before start being used.
     */
    protected void init() {
        checkAndUpdateHealthyServers().join();
        scheduleCheckAndUpdateHealthyServers();
    }

    /**
     * Returns the {@link ClientFactory} that will process {@link EndpointHealthChecker}'s healthcheck requests.
     */
    protected final ClientFactory clientFactory() {
        return clientFactory;
    }

    private void scheduleCheckAndUpdateHealthyServers() {
        clientFactory.eventLoopGroup().schedule(
                () -> checkAndUpdateHealthyServers().thenRun(this::scheduleCheckAndUpdateHealthyServers),
                healthCheckRetryInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<Void> checkAndUpdateHealthyServers() {
        List<ServerConnection> checkedServers = updateServerList();

        CompletableFuture<List<Boolean>> healthCheckResults = CompletableFutures.successfulAsList(
                checkedServers.stream()
                              .map(connection -> connection.healthChecker.isHealthy(connection.endpoint()))
                              .collect(toImmutableList()),
                t -> false);
        return healthCheckResults.handle(voidFunction((result, thrown) -> {
            ImmutableList.Builder<Endpoint> newHealthyEndpoints = ImmutableList.builder();
            for (int i = 0; i < result.size(); i++) {
                if (result.get(i)) {
                    newHealthyEndpoints.add(checkedServers.get(i).endpoint());
                }
            }
            setEndpoints(newHealthyEndpoints.build());
        }));
    }

    /**
     * Update the servers this health checker client talks to.
     */
    private List<ServerConnection> updateServerList() {
        Map<Endpoint, ServerConnection> allServersByEndpoint = allServers
                .stream()
                .collect(toImmutableMap(ServerConnection::endpoint,
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
                .collect(toImmutableList());
    }

    /**
     * Creates a new {@link EndpointHealthChecker} instance that will check {@code endpoint} healthiness.
     */
    protected abstract EndpointHealthChecker createEndpointHealthChecker(Endpoint endpoint);

    /**
     * Returns a newly-created {@link MeterBinder} which binds the stats about this
     * {@link HealthCheckedEndpointGroup} with the default meter names.
     */
    public MeterBinder newMeterBinder(String groupName) {
        return newMeterBinder(new MeterIdPrefix("armeria.client.endpointGroup", "name", groupName));
    }

    /**
     * Returns a newly-created {@link MeterBinder} which binds the stats about this
     * {@link HealthCheckedEndpointGroup}.
     */
    public MeterBinder newMeterBinder(MeterIdPrefix idPrefix) {
        return new HealthCheckedEndpointGroupMetrics(this, idPrefix);
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
        for (Endpoint endpoint : endpoints()) {
            buf.append(endpoint).append(',');
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

    static final class ServerConnection {
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

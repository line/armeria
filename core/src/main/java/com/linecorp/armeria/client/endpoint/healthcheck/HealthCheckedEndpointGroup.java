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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * An {@link EndpointGroup} decorator that only provides healthy {@link Endpoint}s.
 */
public abstract class HealthCheckedEndpointGroup extends DynamicEndpointGroup {
    static final Backoff DEFAULT_HEALTHCHECK_RETRY_BACKOFF = Backoff.fixed(3000).withJitter(0.2);

    private final ClientFactory clientFactory;
    private final EndpointGroup delegate;
    private final Backoff retryBackoff;

    @Nullable
    private volatile ScheduledFuture<?> scheduledCheck;

    volatile List<ServerConnection> allServers = ImmutableList.of();

    /**
     * Creates a new instance.
     * A subclass being initialized with this constructor must call {@link #init()} before start being used.
     *
     * @deprecated Use {@link #HealthCheckedEndpointGroup(ClientFactory, EndpointGroup, Backoff)}.
     */
    @Deprecated
    protected HealthCheckedEndpointGroup(ClientFactory clientFactory,
                                         EndpointGroup delegate,
                                         Duration retryInterval) {
        this(clientFactory, delegate,
             Backoff.fixed(requireNonNull(retryInterval, "retryInterval").toMillis())
                    .withJitter(0.2));
    }

    /**
     * Creates a new instance.
     * A subclass being initialized with this constructor must call {@link #init()} before start being used.
     */
    protected HealthCheckedEndpointGroup(ClientFactory clientFactory,
                                         EndpointGroup delegate,
                                         Backoff retryBackoff) {
        this.clientFactory = requireNonNull(clientFactory, "clientFactory");
        this.delegate = requireNonNull(delegate, "delegate");
        this.retryBackoff = requireNonNull(retryBackoff, "retryBackoff");
    }

    /**
     * Update healthy servers and start to schedule healthcheck.
     * A subclass being initialized with this constructor must call {@link #init()} before start being used.
     */
    protected void init() {
        checkState(scheduledCheck == null, "init() must only be called once.");

        checkAndUpdateHealthyServers().join();
        scheduleCheckAndUpdateHealthyServers();
    }

    @Override
    public void close() {
        super.close();
        delegate.close();

        ScheduledFuture<?> scheduledCheck = this.scheduledCheck;
        if (scheduledCheck != null) {
            scheduledCheck.cancel(true);
            this.scheduledCheck = null;
        }
    }

    /**
     * Returns the {@link ClientFactory} that will process {@link EndpointHealthChecker}'s healthcheck requests.
     */
    protected final ClientFactory clientFactory() {
        return clientFactory;
    }

    private void scheduleCheckAndUpdateHealthyServers() {
        scheduledCheck = clientFactory.eventLoopGroup().schedule(
                () -> checkAndUpdateHealthyServers().thenRun(this::scheduleCheckAndUpdateHealthyServers),
                retryBackoff.nextDelayMillis(1), TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<Void> checkAndUpdateHealthyServers() {
        final List<ServerConnection> checkedServers = updateServerList();

        final CompletableFuture<List<Boolean>> healthCheckResults = CompletableFutures.successfulAsList(
                checkedServers.stream()
                              .map(connection -> connection.healthChecker.isHealthy(connection.endpoint()))
                              .collect(toImmutableList()),
                t -> false);
        return healthCheckResults.handle((result, thrown) -> {
            final ImmutableList.Builder<Endpoint> newHealthyEndpoints = ImmutableList.builder();
            for (int i = 0; i < result.size(); i++) {
                if (result.get(i)) {
                    newHealthyEndpoints.add(checkedServers.get(i).endpoint());
                }
            }
            setEndpoints(newHealthyEndpoints.build());
            return null;
        });
    }

    /**
     * Update the servers this health checker client talks to.
     */
    private List<ServerConnection> updateServerList() {
        final Map<Endpoint, ServerConnection> allServersByEndpoint = allServers
                .stream()
                .collect(toImmutableMap(ServerConnection::endpoint,
                                        Function.identity(),
                                        (l, r) -> l));
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
        final StringBuilder buf = new StringBuilder();
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

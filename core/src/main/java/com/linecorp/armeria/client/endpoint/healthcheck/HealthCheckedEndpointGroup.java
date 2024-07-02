/*
 * Copyright 2019 LINE Corporation
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

import static com.linecorp.armeria.internal.client.endpoint.healthcheck.HealthCheckAttributes.HEALTHY_ATTR;
import static com.linecorp.armeria.internal.common.util.CollectionUtil.truncate;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.internal.client.endpoint.healthcheck.HealthCheckedEndpointPool;

import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * An {@link EndpointGroup} that filters out unhealthy {@link Endpoint}s from an existing {@link EndpointGroup},
 * by sending periodic health check requests.
 *
 * <pre>{@code
 * EndpointGroup originalGroup = ...
 *
 * // Decorate the EndpointGroup with HealthCheckedEndpointGroup
 * // that sends HTTP health check requests to '/internal/l7check' every 10 seconds.
 * HealthCheckedEndpointGroup healthCheckedGroup =
 *         HealthCheckedEndpointGroup.builder(originalGroup, "/internal/l7check")
 *                                   .protocol(SessionProtocol.HTTP)
 *                                   .retryInterval(Duration.ofSeconds(10))
 *                                   .build();
 *
 * // You must specify healthCheckedGroup when building a WebClient, otherwise health checking
 * // will not be enabled.
 * WebClient client = WebClient.builder(SessionProtocol.HTTP, healthCheckedGroup)
 *                             .build();
 * }</pre>
 */
public final class HealthCheckedEndpointGroup extends DynamicEndpointGroup {

    /**
     * Returns a newly created {@link HealthCheckedEndpointGroup} that sends HTTP {@code HEAD} health check
     * requests with default options.
     *
     * @param delegate the {@link EndpointGroup} that provides the candidate {@link Endpoint}s
     * @param path     the HTTP request path, e.g. {@code "/internal/l7check"}
     */
    public static HealthCheckedEndpointGroup of(EndpointGroup delegate, String path) {
        return builder(delegate, path).build();
    }

    /**
     * Returns a newly created {@link HealthCheckedEndpointGroupBuilder} that builds
     * a {@link HealthCheckedEndpointGroup} which sends HTTP {@code HEAD} health check requests.
     *
     * @param delegate the {@link EndpointGroup} that provides the candidate {@link Endpoint}s
     * @param path     the HTTP request path, e.g. {@code "/internal/l7check"}
     */
    public static HealthCheckedEndpointGroupBuilder builder(EndpointGroup delegate, String path) {
        return new HealthCheckedEndpointGroupBuilder(delegate, path);
    }

    final EndpointGroup delegate;
    private final long initialSelectionTimeoutMillis;
    private final long selectionTimeoutMillis;
    @VisibleForTesting
    final HealthCheckStrategy healthCheckStrategy;

    private volatile boolean initialized;
    private final HealthCheckedEndpointPool endpointPool;
    private final Consumer<List<Endpoint>> endpointPoolListener = this::setFilteredEndpoints;

    /**
     * Creates a new instance.
     */
    HealthCheckedEndpointGroup(
            EndpointGroup delegate, boolean allowEmptyEndpoints,
            long initialSelectionTimeoutMillis, long selectionTimeoutMillis,
            Backoff retryBackoff, ClientOptions clientOptions,
            Function<? super HealthCheckerContext, ? extends AsyncCloseable> checkerFactory,
            HealthCheckStrategy healthCheckStrategy,
            Function<Endpoint, HealthCheckerParams> paramsFactory) {

        super(requireNonNull(delegate, "delegate").selectionStrategy(), allowEmptyEndpoints);

        this.delegate = delegate;
        this.initialSelectionTimeoutMillis = initialSelectionTimeoutMillis;
        this.selectionTimeoutMillis = selectionTimeoutMillis;
        endpointPool = new HealthCheckedEndpointPool(retryBackoff,
                                                     clientOptions, checkerFactory, paramsFactory);
        this.healthCheckStrategy = requireNonNull(healthCheckStrategy, "healthCheckStrategy");

        clientOptions.factory().whenClosed().thenRun(this::closeAsync);
        delegate.addListener(this::setCandidates, true);
        endpointPool.addListener(endpointPoolListener, true);
    }

    private void setCandidates(List<Endpoint> endpoints) {
        final List<Endpoint> candidates = healthCheckStrategy.select(endpoints);
        endpointPool.setEndpoints(candidates);
    }

    @VisibleForTesting
    HealthCheckedEndpointPool endpointPool() {
        return endpointPool;
    }

    private void setFilteredEndpoints(List<Endpoint> endpoints) {
        initialized = true;
        final List<Endpoint> healthyEndpoints =
                endpoints.stream().filter(endpoint -> Boolean.TRUE.equals(endpoint.attr(HEALTHY_ATTR)))
                         .collect(Collectors.toList());
        setEndpoints(healthyEndpoints);
    }

    @Override
    public long selectionTimeoutMillis() {
        return initialized ? selectionTimeoutMillis : initialSelectionTimeoutMillis;
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        endpointPool.removeListener(endpointPoolListener);
        CompletableFutures.allAsList(ImmutableList.of(endpointPool.closeAsync(), delegate.closeAsync()))
                          .handle((ignored, cause) -> {
                              future.complete(null);
                              return null;
                          });
    }

    /**
     * Returns a newly-created {@link MeterBinder} which binds the stats about this
     * {@link HealthCheckedEndpointGroup} with the default meter names.
     */
    public MeterBinder newMeterBinder(String groupName) {
        return newMeterBinder(new MeterIdPrefix("armeria.client.endpoint.group", "name", groupName));
    }

    /**
     * Returns a newly-created {@link MeterBinder} which binds the stats about this
     * {@link HealthCheckedEndpointGroup}.
     */
    public MeterBinder newMeterBinder(MeterIdPrefix idPrefix) {
        return new HealthCheckedEndpointGroupMetrics(this, idPrefix);
    }

    @SuppressWarnings("GuardedBy")
    @Override
    public String toString() {
        final List<Endpoint> endpoints = endpoints();
        final List<Endpoint> delegateEndpoints = delegate.endpoints();
        return MoreObjects.toStringHelper(this)
                          .add("endpoints", truncate(endpoints, 10))
                          .add("numEndpoints", endpoints.size())
                          .add("candidates", truncate(delegateEndpoints, 10))
                          .add("numCandidates", delegateEndpoints.size())
                          .add("selectionStrategy", selectionStrategy().getClass())
                          .add("initialized", whenReady().isDone())
                          .add("initialSelectionTimeoutMillis", initialSelectionTimeoutMillis)
                          .add("selectionTimeoutMillis", selectionTimeoutMillis)
                          .add("endpointPool", endpointPool)
                          .toString();
    }
}

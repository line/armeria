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

import static com.linecorp.armeria.internal.common.util.CollectionUtil.truncate;
import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

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

    private static final Logger logger = LoggerFactory.getLogger(HealthCheckedEndpointGroup.class);

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
    private final SessionProtocol protocol;
    private final int port;
    private final Backoff retryBackoff;
    private final ClientOptions clientOptions;
    private final Function<? super HealthCheckerContext, ? extends AsyncCloseable> checkerFactory;
    @VisibleForTesting
    final HealthCheckStrategy healthCheckStrategy;

    private final ReentrantLock lock = new ReentrantShortLock();
    @GuardedBy("lock")
    private final Deque<HealthCheckContextGroup> contextGroupChain = new ArrayDeque<>(4);

    // Should not use NonBlockingHashSet whose remove operation does not clear the reference of the value
    // from the internal array. The remaining value is revived if a new value having the same hash code is
    // added.
    @VisibleForTesting
    final Set<Endpoint> healthyEndpoints = ConcurrentHashMap.newKeySet();
    private volatile boolean initialized;

    /**
     * Creates a new instance.
     */
    HealthCheckedEndpointGroup(
            EndpointGroup delegate, boolean allowEmptyEndpoints,
            long initialSelectionTimeoutMillis, long selectionTimeoutMillis,
            SessionProtocol protocol, int port,
            Backoff retryBackoff, ClientOptions clientOptions,
            Function<? super HealthCheckerContext, ? extends AsyncCloseable> checkerFactory,
            HealthCheckStrategy healthCheckStrategy) {

        super(requireNonNull(delegate, "delegate").selectionStrategy(), allowEmptyEndpoints);

        this.delegate = delegate;
        this.initialSelectionTimeoutMillis = initialSelectionTimeoutMillis;
        this.selectionTimeoutMillis = selectionTimeoutMillis;
        this.protocol = requireNonNull(protocol, "protocol");
        this.port = port;
        this.retryBackoff = requireNonNull(retryBackoff, "retryBackoff");
        this.clientOptions = requireNonNull(clientOptions, "clientOptions");
        this.checkerFactory = requireNonNull(checkerFactory, "checkerFactory");
        this.healthCheckStrategy = requireNonNull(healthCheckStrategy, "healthCheckStrategy");

        clientOptions.factory().whenClosed().thenRun(this::closeAsync);
        delegate.addListener(this::setCandidates, true);
    }

    private void setCandidates(List<Endpoint> endpoints) {
        final List<Endpoint> candidates = healthCheckStrategy.select(endpoints);
        final HashMap<Endpoint, DefaultHealthCheckerContext> contexts = new HashMap<>(candidates.size());

        lock.lock();
        try {
            for (Endpoint endpoint : candidates) {
                if (contexts.containsKey(endpoint)) {
                    continue;
                }
                final DefaultHealthCheckerContext context = findContext(endpoint);
                if (context != null) {
                    contexts.put(endpoint, context.retain());
                } else {
                    contexts.computeIfAbsent(endpoint, this::newCheckerContext);
                }
            }

            final HealthCheckContextGroup contextGroup = new HealthCheckContextGroup(contexts, candidates,
                                                                                     checkerFactory);
            // 'updateHealth()' that retrieves 'contextGroupChain' could be invoked while initializing
            // HealthCheckerContext. For this reason, 'contexts' should be added to 'contextGroupChain'
            // before 'contextGroup.initialize()'.
            contextGroupChain.add(contextGroup);
            contextGroup.initialize();

            // Remove old contexts when the newly created contexts are fully initialized to smoothly transition
            // to new endpoints.
            contextGroup.whenInitialized().handle((unused, cause) -> {
                if (cause != null && !initialized) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("The first health check failed for all endpoints. " +
                                    "numCandidates: {} candidates: {}",
                                    candidates.size(), truncate(candidates, 10), cause);
                    }
                }
                initialized = true;
                destroyOldContexts(contextGroup);
                setEndpoints(allHealthyEndpoints());
                return null;
            });
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("GuardedBy")
    @VisibleForTesting
    Queue<HealthCheckContextGroup> contextGroupChain() {
        return contextGroupChain;
    }

    @VisibleForTesting
    List<Endpoint> allHealthyEndpoints() {
        lock.lock();
        try {
            final HealthCheckContextGroup newGroup = contextGroupChain.peekLast();
            if (newGroup == null) {
                return ImmutableList.of();
            }

            final List<Endpoint> allHealthyEndpoints = new ArrayList<>();
            for (Endpoint candidate : newGroup.candidates()) {
                if (healthyEndpoints.contains(candidate)) {
                    allHealthyEndpoints.add(candidate);
                }
            }

            for (HealthCheckContextGroup oldGroup : contextGroupChain) {
                if (oldGroup == newGroup) {
                    break;
                }
                for (Endpoint candidate : oldGroup.candidates()) {
                    if (!allHealthyEndpoints.contains(candidate) && healthyEndpoints.contains(candidate)) {
                        // Add old Endpoints that do not exist in newGroup. When the first check for newGroup is
                        // completed, the old Endpoints will be removed.
                        allHealthyEndpoints.add(candidate);
                    }
                }
            }
            return allHealthyEndpoints;
        } finally {
            lock.unlock();
        }
    }

    @Nullable
    private DefaultHealthCheckerContext findContext(Endpoint endpoint) {
        lock.lock();
        try {
            for (HealthCheckContextGroup contextGroup : contextGroupChain) {
                final DefaultHealthCheckerContext context = contextGroup.contexts().get(endpoint);
                if (context != null) {
                    return context;
                }
            }
        } finally {
            lock.unlock();
        }
        return null;
    }

    private DefaultHealthCheckerContext newCheckerContext(Endpoint endpoint) {
        return new DefaultHealthCheckerContext(endpoint, port, protocol, clientOptions, retryBackoff,
                                               this::updateHealth);
    }

    private void destroyOldContexts(HealthCheckContextGroup contextGroup) {
        lock.lock();
        try {
            if (!contextGroupChain.contains(contextGroup)) {
                // The contextGroup is already removed by another callback of `contextGroup.whenInitialized()`.
                return;
            }
            final Iterator<HealthCheckContextGroup> it = contextGroupChain.iterator();
            while (it.hasNext()) {
                final HealthCheckContextGroup maybeOldGroup = it.next();
                if (maybeOldGroup == contextGroup) {
                    break;
                }
                for (DefaultHealthCheckerContext context : maybeOldGroup.contexts().values()) {
                    context.release();
                }
                it.remove();
            }
        } finally {
            lock.unlock();
        }
    }

    private void updateHealth(Endpoint endpoint, boolean health) {
        final boolean updated;
        // A healthy endpoint should be a valid checker context.
        if (health && findContext(endpoint) != null) {
            updated = healthyEndpoints.add(endpoint);
        } else {
            updated = healthyEndpoints.remove(endpoint);
        }

        // Each new health status will be updated after initialization of the first context group.
        if (updated && initialized) {
            setEndpoints(allHealthyEndpoints());
        }
    }

    @Override
    public long selectionTimeoutMillis() {
        return initialized ? selectionTimeoutMillis : initialSelectionTimeoutMillis;
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        // Stop the health checkers in parallel.
        final CompletableFuture<?> stopFutures;
        lock.lock();
        try {
            final ImmutableList.Builder<CompletableFuture<?>> completionFutures = ImmutableList.builder();
            for (HealthCheckContextGroup group : contextGroupChain) {
                for (DefaultHealthCheckerContext context : group.contexts().values()) {
                    try {
                        final CompletableFuture<?> closeFuture = context.release();
                        if (closeFuture != null) {
                            completionFutures.add(closeFuture.exceptionally(cause -> {
                                logger.warn("Failed to stop a health checker for: {}",
                                            context.endpoint(), cause);
                                return null;
                            }));
                        }
                    } catch (Exception ex) {
                        logger.warn("Unexpected exception while closing a health checker for: {}",
                                    context.endpoint(), ex);
                    }
                }
            }
            stopFutures = CompletableFutures.allAsList(completionFutures.build());
        } finally {
            lock.unlock();
        }

        stopFutures.handle((unused1, unused2) -> {
            lock.lock();
            try {
                contextGroupChain.clear();
            } finally {
                lock.unlock();
            }
            return delegate.closeAsync();
        }).handle((unused1, unused2) -> future.complete(null));
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
                          .add("contextGroupChain", contextGroupChain)
                          .toString();
    }
}

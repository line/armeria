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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.jctools.maps.NonBlockingHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.internal.eventloop.EventLoopCheckingCompletableFuture;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;

/**
 * An {@link EndpointGroup} that filters out unhealthy {@link Endpoint}s from an existing {@link EndpointGroup},
 * by sending periodic health check requests.
 */
public final class HealthCheckedEndpointGroup extends DynamicEndpointGroup {

    static final Backoff DEFAULT_HEALTH_CHECK_RETRY_BACKOFF = Backoff.fixed(3000).withJitter(0.2);

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
    private final ClientFactory clientFactory;
    private final SessionProtocol protocol;
    private final int port;
    private final Backoff retryBackoff;
    private final Function<? super ClientOptionsBuilder, ClientOptionsBuilder> clientConfigurator;
    private final Function<? super HealthCheckerContext, ? extends AsyncCloseable> checkerFactory;

    private final Map<Endpoint, DefaultHealthCheckerContext> contexts = new HashMap<>();
    @VisibleForTesting
    final Set<Endpoint> healthyEndpoints = new NonBlockingHashSet<>();
    private volatile boolean closed;

    /**
     * Creates a new instance.
     */
    HealthCheckedEndpointGroup(
            EndpointGroup delegate, ClientFactory clientFactory,
            SessionProtocol protocol, int port, Backoff retryBackoff,
            Function<? super ClientOptionsBuilder, ClientOptionsBuilder> clientConfigurator,
            Function<? super HealthCheckerContext, ? extends AsyncCloseable> checkerFactory) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.clientFactory = requireNonNull(clientFactory, "clientFactory");
        this.protocol = requireNonNull(protocol, "protocol");
        this.port = port;
        this.retryBackoff = requireNonNull(retryBackoff, "retryBackoff");
        this.clientConfigurator = requireNonNull(clientConfigurator, "clientConfigurator");
        this.checkerFactory = requireNonNull(checkerFactory, "checkerFactory");

        delegate.addListener(this::updateCandidates);
        updateCandidates(delegate.initialEndpointsFuture().join());

        // Wait until the initial health of all endpoints are determined.
        final List<DefaultHealthCheckerContext> snapshot;
        synchronized (contexts) {
            snapshot = ImmutableList.copyOf(contexts.values());
        }
        snapshot.forEach(ctx -> ctx.initialCheckFuture.join());

        // If all endpoints are unhealthy, we will not have called setEndpoints even once, meaning listeners
        // aren't notified that we've finished an initial health check. We make sure to refresh endpoints once
        // on initialization to ensure this happens, even if the endpoints are currently empty.
        refreshEndpoints();
    }

    private void updateCandidates(List<Endpoint> candidates) {
        synchronized (contexts) {
            if (closed) {
                return;
            }

            // Stop the health checkers whose endpoints disappeared and destroy their contexts.
            for (final Iterator<Map.Entry<Endpoint, DefaultHealthCheckerContext>> i = contexts.entrySet()
                                                                                              .iterator();
                 i.hasNext();) {
                final Map.Entry<Endpoint, DefaultHealthCheckerContext> e = i.next();
                if (candidates.contains(e.getKey())) {
                    // Not a removed endpoint.
                    continue;
                }

                i.remove();
                e.getValue().destroy();
            }

            // Start the health checkers with new contexts for newly appeared endpoints.
            for (Endpoint e : candidates) {
                if (contexts.containsKey(e)) {
                    // Not a new endpoint.
                    continue;
                }
                final DefaultHealthCheckerContext ctx = new DefaultHealthCheckerContext(e);
                ctx.init(checkerFactory.apply(ctx));
                contexts.put(e, ctx);
            }
        }
    }

    private void refreshEndpoints() {
        // Rebuild the endpoint list and notify.
        setEndpoints(delegate.endpoints().stream()
                             .filter(healthyEndpoints::contains)
                             .collect(toImmutableList()));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        // Note: This method is thread-safe and idempotent as long as
        //       super.close() and delegate.close() are so.
        closed = true;

        // Stop the health checkers in parallel.
        final CompletableFuture<List<Object>> stopFutures;
        synchronized (contexts) {
            stopFutures = CompletableFutures.allAsList(
                    contexts.values().stream()
                            .map(ctx -> ctx.destroy().exceptionally(cause -> {
                                logger.warn("Failed to stop a health checker for: {}",
                                            ctx.endpoint(), cause);
                                return null;
                            }))
                            .collect(toImmutableList()));
            contexts.clear();
        }

        super.close();
        delegate.close();

        // Wait until the health checkers are fully stopped.
        stopFutures.join();
    }

    /**
     * Returns a newly-created {@link MeterBinder} which binds the stats about this
     * {@link HealthCheckedEndpointGroup} with the default meter names.
     */
    public MeterBinder newMeterBinder(String groupName) {
        return newMeterBinder(new MeterIdPrefix(Flags.useLegacyMeterNames() ? "armeria.client.endpointGroup"
                                                                            : "armeria.client.endpoint.group",
                                                "name", groupName));
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
        return MoreObjects.toStringHelper(this)
                          .add("chosen", endpoints())
                          .add("candidates", delegate.endpoints())
                          .toString();
    }

    private final class DefaultHealthCheckerContext
            extends AbstractExecutorService implements HealthCheckerContext, ScheduledExecutorService {

        private final Endpoint originalEndpoint;
        private final Endpoint endpoint;

        /**
         * Keeps the {@link Future}s which were scheduled via this {@link ScheduledExecutorService}.
         * Note that this field is also used as a lock.
         */
        private final Map<Future<?>, Boolean> scheduledFutures = new IdentityHashMap<>();

        @Nullable
        private AsyncCloseable handle;
        final CompletableFuture<?> initialCheckFuture = new EventLoopCheckingCompletableFuture<>();
        private boolean destroyed;

        DefaultHealthCheckerContext(Endpoint endpoint) {
            originalEndpoint = endpoint;

            final int altPort = port;
            if (altPort == 0) {
                this.endpoint = endpoint.withoutDefaultPort(protocol.defaultPort());
            } else if (altPort == protocol.defaultPort()) {
                this.endpoint = endpoint.withoutPort();
            } else {
                this.endpoint = endpoint.withPort(altPort);
            }
        }

        void init(AsyncCloseable handle) {
            assert this.handle == null;
            this.handle = handle;
        }

        CompletableFuture<?> destroy() {
            assert handle != null : handle;
            return handle.closeAsync().handle((unused1, unused2) -> {
                synchronized (scheduledFutures) {
                    if (destroyed) {
                        return null;
                    }

                    destroyed = true;

                    // Cancel all scheduled tasks. Make a copy to prevent ConcurrentModificationException
                    // when the future's handler removes it from scheduledFutures as a result of
                    // the cancellation, which may happen on this thread.
                    if (!scheduledFutures.isEmpty()) {
                        final ImmutableList<Future<?>> copy = ImmutableList.copyOf(scheduledFutures.keySet());
                        copy.forEach(f -> f.cancel(false));
                    }
                }

                updateHealth(0, true);

                return null;
            });
        }

        @Override
        public Endpoint endpoint() {
            return endpoint;
        }

        @Override
        public ClientFactory clientFactory() {
            return clientFactory;
        }

        @Override
        public SessionProtocol protocol() {
            return protocol;
        }

        @Override
        public Function<? super ClientOptionsBuilder, ClientOptionsBuilder> clientConfigurator() {
            return clientConfigurator;
        }

        @Override
        public ScheduledExecutorService executor() {
            return this;
        }

        @Override
        public long nextDelayMillis() {
            final long delayMillis = retryBackoff.nextDelayMillis(1);
            if (delayMillis < 0) {
                throw new IllegalStateException(
                        "retryBackoff.nextDelayMillis(1) returned a negative value for " + endpoint +
                        ": " + delayMillis);
            }

            return delayMillis;
        }

        @Override
        public void updateHealth(double health) {
            updateHealth(health, false);
        }

        private void updateHealth(double health, boolean updateEvenIfDestroyed) {
            final boolean updated;
            synchronized (scheduledFutures) {
                if (!updateEvenIfDestroyed && destroyed) {
                    updated = false;
                } else if (health > 0) {
                    updated = healthyEndpoints.add(originalEndpoint);
                } else {
                    updated = healthyEndpoints.remove(originalEndpoint);
                }
            }

            if (updated) {
                refreshEndpoints();
            }

            initialCheckFuture.complete(null);
        }

        @Override
        public void execute(Runnable command) {
            synchronized (scheduledFutures) {
                rejectIfDestroyed(command);
                add(eventLoopGroup().submit(command));
            }
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            synchronized (scheduledFutures) {
                rejectIfDestroyed(command);
                return add(eventLoopGroup().schedule(command, delay, unit));
            }
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            synchronized (scheduledFutures) {
                rejectIfDestroyed(callable);
                return add(eventLoopGroup().schedule(callable, delay, unit));
            }
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                      TimeUnit unit) {
            synchronized (scheduledFutures) {
                rejectIfDestroyed(command);
                return add(eventLoopGroup().scheduleAtFixedRate(command, initialDelay, period, unit));
            }
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                         TimeUnit unit) {
            synchronized (scheduledFutures) {
                rejectIfDestroyed(command);
                return add(eventLoopGroup().scheduleWithFixedDelay(command, initialDelay, delay, unit));
            }
        }

        @Override
        public void shutdown() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Runnable> shutdownNow() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isShutdown() {
            return eventLoopGroup().isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return eventLoopGroup().isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return eventLoopGroup().awaitTermination(timeout, unit);
        }

        private EventLoopGroup eventLoopGroup() {
            return clientFactory.eventLoopGroup();
        }

        private void rejectIfDestroyed(Object command) {
            if (destroyed) {
                throw new RejectedExecutionException(
                        HealthCheckerContext.class.getSimpleName() + " for '" + endpoint +
                        "' has been destroyed already. Task: " + command);
            }
        }

        private <T extends Future<U>, U> T add(T future) {
            scheduledFutures.put(future, Boolean.TRUE);
            future.addListener(f -> {
                synchronized (scheduledFutures) {
                    scheduledFutures.remove(f);
                }
            });
            return future;
        }
    }
}

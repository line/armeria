/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.client;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

final class ConnectionPoolMetrics implements SafeCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionPoolMetrics.class);

    private static final ScheduledExecutorService CLEANUP_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(
                    ThreadFactories.newThreadFactory("armeria-connection-metric-cleanup-executor",
                                                     true));

    private static final String PROTOCOL = "protocol";
    private static final String REMOTE_IP = "remote.ip";
    private static final String LOCAL_IP = "local.ip";
    private static final String STATE = "state";

    private final MeterRegistry meterRegistry;
    private final MeterIdPrefix idPrefix;
    @GuardedBy("lock")
    private final Map<List<Tag>, Meters> metersMap = new HashMap<>();
    private final ReentrantShortLock lock = new ReentrantShortLock();
    private final int cleanupDelaySeconds;
    private boolean garbageCollecting;

    private volatile boolean closed;
    private volatile ScheduledFuture<?> scheduledFuture;

    /**
     * Creates a new instance with the specified {@link Meter} name.
     */
    ConnectionPoolMetrics(MeterRegistry meterRegistry, MeterIdPrefix idPrefix) {
        this(meterRegistry, idPrefix, 3600 /* 1 hour */);
    }

    @VisibleForTesting
    ConnectionPoolMetrics(MeterRegistry meterRegistry, MeterIdPrefix idPrefix, int cleanupDelaySeconds) {
        this.idPrefix = idPrefix;
        this.meterRegistry = meterRegistry;
        this.cleanupDelaySeconds = cleanupDelaySeconds;
        // Schedule a cleanup task to remove unused meters.
        scheduledFuture = CLEANUP_EXECUTOR.schedule(this::cleanupInactiveMeters,
                                                    nextCleanupDelaySeconds(), TimeUnit.SECONDS);
    }

    void increaseConnOpened(SessionProtocol protocol, InetSocketAddress remoteAddr,
                            InetSocketAddress localAddr) {
        final List<Tag> commonTags = commonTags(protocol, remoteAddr, localAddr);
        lock.lock();
        try {
            final Meters meters = metersMap.computeIfAbsent(commonTags, Meters::new);
            meters.increment();
        } finally {
            lock.unlock();
        }
    }

    private List<Tag> commonTags(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                 InetSocketAddress localAddr) {
        return idPrefix.tags(PROTOCOL, protocol.name(),
                             REMOTE_IP, remoteAddr.getAddress().getHostAddress(),
                             LOCAL_IP, localAddr.getAddress().getHostAddress());
    }

    void increaseConnClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                            InetSocketAddress localAddr) {

        final List<Tag> commonTags = commonTags(protocol, remoteAddr, localAddr);
        lock.lock();
        try {
            final Meters meters = metersMap.get(commonTags);
            if (meters != null) {
                meters.decrement();
                assert meters.activeConnections() >= 0 : "active connections should not be negative. " + meters;
            }
        } finally {
            lock.unlock();
        }
    }

    void cleanupInactiveMeters() {
        final List<Meters> unusedMetersList = new ArrayList<>();
        try {
            lock.lock();
            // Prevent meter registration while cleaning up.
            garbageCollecting = true;

            // Collect unused meters.
            try {
                for (final Iterator<Entry<List<Tag>, Meters>> it = metersMap.entrySet().iterator();
                     it.hasNext();) {
                    final Entry<List<Tag>, Meters> entry = it.next();
                    final Meters meters = entry.getValue();
                    if (meters.activeConnections() == 0) {
                        unusedMetersList.add(meters);
                        it.remove();
                    }
                }

                if (unusedMetersList.isEmpty()) {
                    garbageCollecting = false;
                    return;
                }
            } finally {
                lock.unlock();
            }

            // Remove unused meters.
            for (Meters meters : unusedMetersList) {
                meters.remove(meterRegistry);
            }

            // Register metrics for the pending meters.
            lock.lock();
            try {
                metersMap.values().forEach(Meters::maybeRegisterMetrics);
                garbageCollecting = false;
            } finally {
                lock.unlock();
            }
        } catch (Throwable e) {
            logger.warn("Failed to cleanup inactive meters.", e);
            garbageCollecting = false;
        }

        if (closed) {
            return;
        }

        // Schedule the next cleanup task.
        scheduledFuture = CLEANUP_EXECUTOR.schedule(this::cleanupInactiveMeters,
                                                    nextCleanupDelaySeconds(), TimeUnit.SECONDS);
    }

    private long nextCleanupDelaySeconds() {
        // Schedule the cleanup task randomly between cleanupDelayMinutes and 2 * cleanupDelayMinutes.
        return cleanupDelaySeconds + ThreadLocalRandom.current().nextInt(cleanupDelaySeconds);
    }

    @Override
    public void close() {
        // This method will be invoked after the connection pool is closed.
        closed = true;
        final ScheduledFuture<?> scheduledFuture = this.scheduledFuture;
        scheduledFuture.cancel(false);
        CLEANUP_EXECUTOR.execute(this::cleanupInactiveMeters);
    }

    private final class Meters {

        private final List<Tag> commonTags;

        @Nullable
        private Counter opened;
        @Nullable
        private Counter closed;
        @Nullable
        private Gauge active;

        private int numOpened;
        private int numClosed;

        Meters(List<Tag> commonTags) {
            this.commonTags = commonTags;
            if (!garbageCollecting) {
                maybeRegisterMetrics();
            }
        }

        void maybeRegisterMetrics() {
            if (opened != null) {
                return;
            }

            opened = Counter.builder(idPrefix.name("connections"))
                            .tags(commonTags)
                            .tag(STATE, "opened")
                            .register(meterRegistry);
            if (numOpened > 0) {
                opened.increment(numOpened);
            }

            closed = Counter.builder(idPrefix.name("connections"))
                            .tags(commonTags)
                            .tag(STATE, "closed")
                            .register(meterRegistry);
            if (numClosed > 0) {
                closed.increment(numClosed);
            }

            active = Gauge.builder(idPrefix.name("active.connections"), this, Meters::activeConnections)
                          .tags(commonTags)
                          .register(meterRegistry);
        }

        void increment() {
            numOpened++;
            if (opened != null) {
                opened.increment();
            }
        }

        void decrement() {
            numClosed++;
            if (closed != null) {
                closed.increment();
            }
        }

        int activeConnections() {
            return numOpened - numClosed;
        }

        void remove(MeterRegistry registry) {
            if (opened != null) {
                assert closed != null;
                assert active != null;
                registry.remove(opened);
                registry.remove(closed);
                registry.remove(active);
            }
        }
    }
}

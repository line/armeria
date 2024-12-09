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

import com.google.common.annotations.VisibleForTesting;
import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.common.SessionProtocol;
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
            final Meters meters = metersMap.computeIfAbsent(commonTags,
                                                            key -> new Meters(idPrefix, key, meterRegistry));
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
        lock.lock();
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
        } finally {
            lock.unlock();
        }

        for (Meters meters : unusedMetersList) {
            meters.remove(meterRegistry);
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

    private static final class Meters {

        private final Counter opened;
        private final Counter closed;
        private final Gauge active;
        private int activeConnections;

        Meters(MeterIdPrefix idPrefix, List<Tag> commonTags, MeterRegistry registry) {
            opened = Counter.builder(idPrefix.name("connections"))
                            .tags(commonTags)
                            .tag(STATE, "opened")
                            .register(registry);
            closed = Counter.builder(idPrefix.name("connections"))
                            .tags(commonTags)
                            .tag(STATE, "closed")
                            .register(registry);
            active = Gauge.builder(idPrefix.name("active.connections"), this, Meters::activeConnections)
                          .tags(commonTags)
                          .register(registry);
        }

        Meters increment() {
            activeConnections++;
            opened.increment();
            return this;
        }

        Meters decrement() {
            activeConnections--;
            closed.increment();
            return this;
        }

        int activeConnections() {
            return activeConnections;
        }

        void remove(MeterRegistry registry) {
            registry.remove(opened);
            registry.remove(closed);
            registry.remove(active);
        }
    }
}

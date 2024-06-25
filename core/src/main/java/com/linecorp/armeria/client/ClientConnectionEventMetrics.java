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
package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

final class ClientConnectionEventMetrics {
    private static final String DESIRED_PROTOCOL = "desired.protocol";
    private static final String PROTOCOL = "protocol";
    private static final String REMOTE_IP = "remote.ip";
    private static final String LOCAL_IP = "local.ip";
    private static final String STATE = "state";

    private final MeterRegistry meterRegistry;
    private final MeterIdPrefix idPrefix;
    @GuardedBy("lock")
    private final Map<List<Tag>, ConnRequestMeters> connRequestMetersMap = new HashMap<>();
    @GuardedBy("lock")
    private final Map<List<Tag>, ConnMeters> connMetersMap = new HashMap<>();
    private final ReentrantShortLock lock = new ReentrantShortLock();

    /**
     * Creates a new instance with the specified {@link Meter} name.
     */
    ClientConnectionEventMetrics(MeterRegistry meterRegistry, MeterIdPrefix idPrefix) {
        requireNonNull(meterRegistry, "registry");
        requireNonNull(idPrefix, "idPrefix");

        this.idPrefix = idPrefix;
        this.meterRegistry = meterRegistry;
    }

    void pending(SessionProtocol desiredProtocol,
                 InetSocketAddress remoteAddress,
                 InetSocketAddress localAddress) {
        final List<Tag> commonTags = ConnRequestMeters.commonTags(idPrefix, desiredProtocol,
                                                                  remoteAddress, localAddress);

        lock.lock();
        try {
            final ConnRequestMeters meters =
                    connRequestMetersMap.computeIfAbsent(commonTags, key ->
                            new ConnRequestMeters(idPrefix, key, meterRegistry));

            meters.incrementPending();
        } finally {
            lock.unlock();
        }
    }

    void failed(SessionProtocol desiredProtocol,
                InetSocketAddress remoteAddress,
                InetSocketAddress localAddress) {
        final List<Tag> commonTags = ConnRequestMeters.commonTags(idPrefix, desiredProtocol,
                                                                  remoteAddress, localAddress);

        lock.lock();
        try {
            final ConnRequestMeters meters = connRequestMetersMap.get(commonTags);

            if (meters == null) {
                return;
            }

            meters.failed();

            if (meters.pendingConnections() == 0) {
                connRequestMetersMap.remove(commonTags);
                meters.remove(meterRegistry);
            }
        } finally {
            lock.unlock();
        }
    }

    void opened(SessionProtocol desiredProtocol,
                SessionProtocol actualProtocol,
                InetSocketAddress remoteAddress,
                InetSocketAddress localAddress) {
        final List<Tag> connRequestCommonTags = ConnRequestMeters.commonTags(idPrefix, desiredProtocol,
                                                                             remoteAddress, localAddress);

        final List<Tag> connCommonTags = ConnMeters.commonTags(idPrefix, actualProtocol,
                                                                  remoteAddress, localAddress);

        lock.lock();
        try {
            final ConnRequestMeters requestMeters = connRequestMetersMap
                    .computeIfAbsent(connRequestCommonTags,
                                     key -> new ConnRequestMeters(idPrefix, key, meterRegistry));
            requestMeters.decrementPending();

            if (requestMeters.pendingConnections() == 0) {
                connRequestMetersMap.remove(connRequestCommonTags);
                requestMeters.remove(meterRegistry);
            }

            final ConnMeters connMeters = connMetersMap
                    .computeIfAbsent(connCommonTags, key -> new ConnMeters(idPrefix, key, meterRegistry));
            connMeters.open();
        } finally {
            lock.unlock();
        }
    }

    void closed(SessionProtocol actualProtocol,
                InetSocketAddress remoteAddress,
                InetSocketAddress localAddress,
                @Nullable Boolean isActive) {
        final List<Tag> commonTags = ConnMeters.commonTags(idPrefix, actualProtocol,
                                                           remoteAddress, localAddress);

        lock.lock();
        try {
            final ConnMeters meters = connMetersMap.get(commonTags);

            if (meters == null) {
                return;
            }

            meters.close(isActive);

            if (meters.activeConnections() + meters.idleConnections == 0) {
                connMetersMap.remove(commonTags);
                meters.remove(meterRegistry);
            }
        } finally {
            lock.unlock();
        }
    }

    void active(SessionProtocol actualProtocol,
                InetSocketAddress remoteAddress,
                InetSocketAddress localAddress,
                boolean isNew) {
        final List<Tag> commonTags = ConnMeters.commonTags(idPrefix, actualProtocol,
                                                           remoteAddress, localAddress);

        lock.lock();
        try {
            final ConnMeters meters = connMetersMap.get(commonTags);

            if (meters == null) {
                return;
            }

            meters.active(isNew);
        } finally {
            lock.unlock();
        }
    }

    void idle(SessionProtocol actualProtocol,
              InetSocketAddress remoteAddress,
              InetSocketAddress localAddress) {
        final List<Tag> commonTags = ConnMeters.commonTags(idPrefix, actualProtocol,
                                                           remoteAddress, localAddress);

        lock.lock();
        try {
            final ConnMeters meters = connMetersMap.get(commonTags);

            if (meters == null) {
                return;
            }

            meters.idle();
        } finally {
            lock.unlock();
        }
    }

    private static final class ConnRequestMeters {
        private final Gauge pending;
        private final Counter failed;
        private int pendingConnections;

        static List<Tag> commonTags(MeterIdPrefix idPrefix,
                       SessionProtocol desiredProtocol,
                       InetSocketAddress remoteAddress,
                       InetSocketAddress localAddress) {
            return idPrefix.tags(DESIRED_PROTOCOL, desiredProtocol.name(),
                                 REMOTE_IP, remoteAddress.getAddress().getHostAddress(),
                                 LOCAL_IP, localAddress.getAddress().getHostAddress());
        }

        ConnRequestMeters(MeterIdPrefix idPrefix, List<Tag> commonTags, MeterRegistry registry) {
            pending = Gauge.builder(idPrefix.name("connection.pool.size"),
                                    this,ConnRequestMeters::pendingConnections)
                            .tags(commonTags)
                            .tag(STATE, "pending")
                            .register(registry);
            failed = Counter.builder(idPrefix.name("connections"))
                            .tags(commonTags)
                            .tag(STATE, "failed")
                            .register(registry);
        }

        ConnRequestMeters incrementPending() {
            pendingConnections++;
            return this;
        }

        ConnRequestMeters decrementPending() {
            pendingConnections--;
            return this;
        }

        ConnRequestMeters failed() {
            pendingConnections--;
            failed.increment();
            return this;
        }

        public int pendingConnections() {
            return pendingConnections;
        }

        void remove(MeterRegistry registry) {
            registry.remove(pending);
            registry.remove(failed);
        }
    }

    private static final class ConnMeters {
        static List<Tag> commonTags(MeterIdPrefix idPrefix, SessionProtocol actualProtocol,
                                    InetSocketAddress remoteAddress, InetSocketAddress localAddress) {
            return idPrefix.tags(PROTOCOL, actualProtocol.name(),
                                 REMOTE_IP, remoteAddress.getAddress().getHostAddress(),
                                 LOCAL_IP, localAddress.getAddress().getHostAddress());
        }

        private final Counter opened;
        private final Counter closed;
        private final Gauge active;
        private final Gauge idle;
        private int activeConnections;
        private int idleConnections;

        ConnMeters(MeterIdPrefix idPrefix, List<Tag> commonTags, MeterRegistry registry) {
            opened = Counter.builder(idPrefix.name("connections"))
                            .tags(commonTags)
                            .tag(STATE, "opened")
                            .register(registry);
            closed = Counter.builder(idPrefix.name("connections"))
                            .tags(commonTags)
                            .tag(STATE, "closed")
                            .register(registry);
            active = Gauge.builder(idPrefix.name("connection.pool.size"), this, ConnMeters::activeConnections)
                          .tags(commonTags)
                          .tag(STATE, "active")
                          .register(registry);
            idle = Gauge.builder(idPrefix.name("connection.pool.size"), this, ConnMeters::idleConnections)
                          .tags(commonTags)
                        .tag(STATE, "idle")
                          .register(registry);
        }

        ConnMeters open() {
            opened.increment();
            return this;
        }

        ConnMeters close(@Nullable Boolean isActive) {
            if (isActive != null) {
                if (isActive) {
                    activeConnections--;
                } else {
                    idleConnections--;
                }
            }
            closed.increment();
            return this;
        }

        ConnMeters idle() {
            activeConnections--;
            idleConnections++;
            return this;
        }

        ConnMeters active(boolean isNew) {
            activeConnections++;
            if (!isNew) {
                idleConnections--;
            }
            return this;
        }

        int activeConnections() {
            return activeConnections;
        }

        public int idleConnections() {
            return idleConnections;
        }

        void remove(MeterRegistry registry) {
            registry.remove(opened);
            registry.remove(closed);
            registry.remove(active);
            registry.remove(idle);
        }
    }
}

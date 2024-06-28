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
import io.netty.util.AttributeMap;

final class MetricCollectingClientConnectionEventListener implements ClientConnectionEventListener {
    private static final String DESIRED_PROTOCOL = "desired.protocol";
    private static final String PROTOCOL = "protocol";
    private static final String REMOTE_IP = "remote.ip";
    private static final String LOCAL_IP = "local.ip";
    private static final String STATE = "state";

    private final MeterRegistry meterRegistry;
    private final MeterIdPrefix idPrefix;
    @GuardedBy("lock")
    private final Map<List<Tag>, ConnectionAcquisitionMeter> acquisitionMeters = new HashMap<>();
    @GuardedBy("lock")
    private final Map<List<Tag>, ConnectionStateMeter> connMeters = new HashMap<>();
    private final ReentrantShortLock lock = new ReentrantShortLock();

    /**
     * Creates a new instance with the specified {@link Meter} name.
     */
    MetricCollectingClientConnectionEventListener(MeterRegistry meterRegistry, MeterIdPrefix idPrefix) {
        requireNonNull(meterRegistry, "registry");
        requireNonNull(idPrefix, "idPrefix");

        this.idPrefix = idPrefix;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void connectionPending(SessionProtocol desiredProtocol, InetSocketAddress remoteAddress,
                                  InetSocketAddress localAddress, AttributeMap attrs) throws Exception {
        final List<Tag> acquisitionTags =
                ConnectionAcquisitionMeter.commonTags(idPrefix, desiredProtocol, remoteAddress, localAddress);
        lock.lock();
        try {
            final ConnectionAcquisitionMeter meter =
                    acquisitionMeters.computeIfAbsent(acquisitionTags, key ->
                            new ConnectionAcquisitionMeter(idPrefix, key, meterRegistry));

            meter.incrementPending();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void connectionFailed(SessionProtocol desiredProtocol, InetSocketAddress remoteAddress,
                                 @Nullable InetSocketAddress localAddress,
                                 AttributeMap attrs, Throwable cause, boolean wasPending) {
        if (wasPending) {
            // localAddress is not null if wasPending is true.
            assert localAddress != null;
            final List<Tag> acquisitionTags =
                    ConnectionAcquisitionMeter.commonTags(idPrefix, desiredProtocol, remoteAddress,
                                                          localAddress);
            lock.lock();
            try {
                decreasePending(acquisitionTags);
            } finally {
                lock.unlock();
            }
        }
        final List<Tag> tags = idPrefix.tags(DESIRED_PROTOCOL, desiredProtocol.name(),
                                             REMOTE_IP, remoteAddress.getAddress().getHostAddress());
        final Counter failed = Counter.builder(idPrefix.name("connections"))
                                      .tags(tags)
                                      .tag(STATE, "failed")
                                      .register(meterRegistry);
        failed.increment();
    }

    @Override
    public void connectionOpened(@Nullable SessionProtocol desiredProtocol, SessionProtocol protocol,
                                 InetSocketAddress remoteAddress, InetSocketAddress localAddress,
                                 AttributeMap attrs) throws Exception {
        // desiredProtocol is not null if it's a client connection.
        assert desiredProtocol != null;
        final List<Tag> acquisitionTags =
                ConnectionAcquisitionMeter.commonTags(idPrefix, desiredProtocol,
                                                      remoteAddress, localAddress);
        final List<Tag> stateTags = ConnectionStateMeter.commonTags(idPrefix, protocol, remoteAddress,
                                                                    localAddress);

        lock.lock();
        try {
            decreasePending(acquisitionTags);

            final ConnectionStateMeter connectionStateMeter = connMeters
                    .computeIfAbsent(stateTags,
                                     key -> new ConnectionStateMeter(idPrefix, key, meterRegistry));
            connectionStateMeter.open();
        } finally {
            lock.unlock();
        }
    }

    private void decreasePending(List<Tag> acquisitionTags) {
        final ConnectionAcquisitionMeter requestMeters = acquisitionMeters
                .computeIfAbsent(acquisitionTags,
                                 key -> new ConnectionAcquisitionMeter(idPrefix, key, meterRegistry));
        requestMeters.decrementPending();
        if (requestMeters.pendingConnections() == 0) {
            acquisitionMeters.remove(acquisitionTags);
            requestMeters.remove(meterRegistry);
        }
    }

    @Override
    public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress, AttributeMap attrs, boolean wasIdle) {
        final List<Tag> commonTags = ConnectionStateMeter.commonTags(idPrefix, protocol,
                                                                     remoteAddress, localAddress);

        lock.lock();
        try {
            final ConnectionStateMeter meters = connMeters.get(commonTags);

            if (meters == null) {
                return;
            }

            meters.close(wasIdle);

            if (meters.activeConnections() + meters.idleConnections == 0) {
                connMeters.remove(commonTags);
                meters.remove(meterRegistry);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void connectionActive(SessionProtocol protocol, InetSocketAddress remoteAddress,
                                 InetSocketAddress localAddress, AttributeMap attrs, boolean wasIdle) {
        final List<Tag> commonTags = ConnectionStateMeter.commonTags(idPrefix, protocol,
                                                                     remoteAddress, localAddress);

        lock.lock();
        try {
            final ConnectionStateMeter meters = connMeters.get(commonTags);

            if (meters == null) {
                return;
            }

            meters.active(wasIdle);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void connectionIdle(SessionProtocol protocol, InetSocketAddress remoteAddress,
                               InetSocketAddress localAddress, AttributeMap attrs) throws Exception {
        final List<Tag> commonTags = ConnectionStateMeter.commonTags(idPrefix, protocol,
                                                                     remoteAddress, localAddress);

        lock.lock();
        try {
            final ConnectionStateMeter meters = connMeters.get(commonTags);

            if (meters == null) {
                return;
            }

            meters.idle();
        } finally {
            lock.unlock();
        }
    }

    private static final class ConnectionAcquisitionMeter {
        private final Gauge pending;
        private int pendingConnections;

        static List<Tag> commonTags(MeterIdPrefix idPrefix,
                                    SessionProtocol desiredProtocol,
                                    InetSocketAddress remoteAddress,
                                    InetSocketAddress localAddress) {
            return idPrefix.tags(DESIRED_PROTOCOL, desiredProtocol.name(),
                                 REMOTE_IP, remoteAddress.getAddress().getHostAddress(),
                                 LOCAL_IP, localAddress.getAddress().getHostAddress());
        }

        ConnectionAcquisitionMeter(MeterIdPrefix idPrefix, List<Tag> commonTags, MeterRegistry registry) {
            pending = Gauge.builder(idPrefix.name("connection.pool.size"),
                                    this, ConnectionAcquisitionMeter::pendingConnections)
                           .tags(commonTags)
                           .tag(STATE, "pending")
                           .register(registry);
        }

        ConnectionAcquisitionMeter incrementPending() {
            pendingConnections++;
            return this;
        }

        ConnectionAcquisitionMeter decrementPending() {
            pendingConnections--;
            return this;
        }

        public int pendingConnections() {
            return pendingConnections;
        }

        void remove(MeterRegistry registry) {
            registry.remove(pending);
        }
    }

    private static final class ConnectionStateMeter {
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

        ConnectionStateMeter(MeterIdPrefix idPrefix, List<Tag> commonTags, MeterRegistry registry) {
            opened = Counter.builder(idPrefix.name("connections"))
                            .tags(commonTags)
                            .tag(STATE, "opened")
                            .register(registry);
            closed = Counter.builder(idPrefix.name("connections"))
                            .tags(commonTags)
                            .tag(STATE, "closed")
                            .register(registry);
            active = Gauge.builder(idPrefix.name("connection.pool.size"), this,
                                   ConnectionStateMeter::activeConnections)
                          .tags(commonTags)
                          .tag(STATE, "active")
                          .register(registry);
            idle = Gauge.builder(idPrefix.name("connection.pool.size"), this,
                                 ConnectionStateMeter::idleConnections)
                        .tags(commonTags)
                        .tag(STATE, "idle")
                        .register(registry);
        }

        ConnectionStateMeter open() {
            opened.increment();
            return this;
        }

        ConnectionStateMeter close(boolean wasIdle) {
            if (wasIdle) {
                idleConnections--;
            } else {
                activeConnections--;
            }
            closed.increment();
            return this;
        }

        ConnectionStateMeter idle() {
            activeConnections--;
            idleConnections++;
            return this;
        }

        ConnectionStateMeter active(boolean wasIdle) {
            activeConnections++;
            if (wasIdle) {
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

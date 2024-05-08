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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

final class ConnectionPoolMetrics {
    private static final String PROTOCOL = "protocol";
    private static final String REMOTE_IP = "remote.ip";
    private static final String LOCAL_IP = "local.ip";
    private static final String STATE = "state";

    private final MeterRegistry meterRegistry;
    private final MeterIdPrefix idPrefix;
    @GuardedBy("lock")
    private final Map<List<Tag>, Meters> metersMap = new HashMap<>();
    private final ReentrantShortLock lock = new ReentrantShortLock();

    /**
     * Creates a new instance with the specified {@link Meter} name.
     */
    ConnectionPoolMetrics(MeterRegistry meterRegistry, MeterIdPrefix idPrefix) {
        this.idPrefix = idPrefix;
        this.meterRegistry = meterRegistry;
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
                if (meters.activeConnections() == 0) {
                    // XXX(ikhoon): Should we consider to remove the gauge lazily so that collectors can get the
                    //              value.
                    // Remove gauges to be garbage collected because the cardinality of remoteAddr could be
                    // high.
                    metersMap.remove(commonTags);
                    meters.remove(meterRegistry);
                }
            }
        } finally {
            lock.unlock();
        }
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

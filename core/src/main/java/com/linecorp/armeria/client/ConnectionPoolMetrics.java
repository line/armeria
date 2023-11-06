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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

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
    private final Map<List<Tag>, Integer> activeConnections = new ConcurrentHashMap<>();

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
        Counter.builder(idPrefix.name())
               .tags(commonTags)
               .tag(STATE, "opened")
               .register(meterRegistry)
               .increment();

        final int numConnections = activeConnections.compute(commonTags, (k, v) -> v == null ? 1 : v + 1);
        if (numConnections == 1) {
            Gauge.builder(idPrefix.name(),
                          activeConnections,
                          activeConnections -> activeConnections.getOrDefault(commonTags, 0))
                 .tags(commonTags)
                 .tag(STATE, "active")
                 .register(meterRegistry);
        }
    }

    @Nonnull
    private List<Tag> commonTags(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                 InetSocketAddress localAddr) {
        return idPrefix.tags(PROTOCOL, protocol.name(),
                             REMOTE_IP, remoteAddr.getAddress().getHostAddress(),
                             LOCAL_IP, localAddr.getAddress().getHostAddress());
    }

    void increaseConnClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                            InetSocketAddress localAddr) {
        final List<Tag> commonTags = commonTags(protocol, remoteAddr, localAddr);
        Counter.builder(idPrefix.name())
               .tags(commonTags)
               .tag(STATE, "closed")
               .register(meterRegistry)
               .increment();

        activeConnections.computeIfPresent(commonTags, (k, v) -> v - 1);
        if (activeConnections.getOrDefault(commonTags, 0) <= 0) {
            // Remove the gauge to be garbage collected.
            activeConnections.remove(commonTags);
        }
    }
}

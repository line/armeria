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

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.AttributeMap;

import java.net.InetSocketAddress;

import static java.util.Objects.requireNonNull;

final class MetricCollectingConnectionPoolListener implements ConnectionPoolListener {
    private final MeterRegistry registry;
    private final String name;

    private final ConnectionPoolMetrics connectionPoolMetrics;

    /**
     * Creates a new instance with the specified {@link Meter} name.
     */
    MetricCollectingConnectionPoolListener(MeterRegistry registry, String name) {
        this.registry = requireNonNull(registry, "registry");
        this.name = requireNonNull(name, "name");

        connectionPoolMetrics = new ConnectionPoolMetrics(registry, new MeterIdPrefix(name));
    }

    @Override
    public void connectionOpen(SessionProtocol protocol, InetSocketAddress remoteAddr,
                               InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
        connectionPoolMetrics.increaseConnOpened();
    }

    @Override
    public void connectionClosed(SessionProtocol protocol, InetSocketAddress remoteAddr,
                                 InetSocketAddress localAddr, AttributeMap attrs) throws Exception {
        connectionPoolMetrics.increaseConnClosed();
    }
}

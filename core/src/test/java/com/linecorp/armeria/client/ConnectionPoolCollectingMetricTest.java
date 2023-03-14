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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MoreMeters;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.util.AttributeMap;
import io.netty.util.DefaultAttributeMap;

class ConnectionPoolCollectingMetricTest {
    private ConnectionPoolListener connectionPoolListener;
    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        connectionPoolListener = ConnectionPoolListener.metricCollecting(registry);
    }

    @Test
    void shouldCollectConnectionPoolEvents() throws Exception {
        final InetSocketAddress addressA = new InetSocketAddress("10.10.10.10", 3333);
        final InetSocketAddress addressB = new InetSocketAddress("10.10.10.11", 3333);

        final String openABMetricKey = "armeria.client.connections#count{local.ip=10.10.10.11," +
                                       "protocol=H1,remote.ip=10.10.10.10,state=opened}";
        final String closedABMetricKey = "armeria.client.connections#count{local.ip=10.10.10.11," +
                                         "protocol=H1,remote.ip=10.10.10.10,state=closed}";
        final String openBAMetricKey = "armeria.client.connections#count{local.ip=10.10.10.10," +
                                       "protocol=H1,remote.ip=10.10.10.11,state=opened}";
        final String closedBAMetricKey = "armeria.client.connections#count{local.ip=10.10.10.10," +
                                         "protocol=H1,remote.ip=10.10.10.11,state=closed}";

        final AttributeMap attributeMap = new DefaultAttributeMap();

        connectionPoolListener.connectionOpen(SessionProtocol.H1, addressA, addressB, attributeMap);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openABMetricKey, 1.0);

        connectionPoolListener.connectionClosed(SessionProtocol.H1, addressA, addressB, attributeMap);
        assertThat(MoreMeters.measureAll(registry))
                .containsEntry(openABMetricKey, 1.0)
                .containsEntry(closedABMetricKey, 1.0);

        connectionPoolListener.connectionOpen(SessionProtocol.H1, addressA, addressB, attributeMap);
        assertThat(MoreMeters.measureAll(registry))
                .containsEntry(openABMetricKey, 2.0)
                .containsEntry(closedABMetricKey, 1.0);

        connectionPoolListener.connectionOpen(SessionProtocol.H1, addressB, addressA, attributeMap);
        assertThat(MoreMeters.measureAll(registry))
                .containsEntry(openABMetricKey, 2.0)
                .containsEntry(closedABMetricKey, 1.0)
                .containsEntry(openBAMetricKey, 1.0);

        connectionPoolListener.connectionClosed(SessionProtocol.H1, addressA, addressB, attributeMap);
        connectionPoolListener.connectionClosed(SessionProtocol.H1, addressB, addressA, attributeMap);
        assertThat(MoreMeters.measureAll(registry))
                .containsEntry(openABMetricKey, 2.0)
                .containsEntry(closedABMetricKey, 2.0)
                .containsEntry(openBAMetricKey, 1.0)
                .containsEntry(closedBAMetricKey, 1.0);
    }
}

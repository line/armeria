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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Maps;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.AttributeMap;
import io.netty.util.DefaultAttributeMap;

class ClientConnectionEventCollectingMetricTest {
    private ClientConnectionEventListener connectionEventListener;
    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = PrometheusMeterRegistries.newRegistry();
        connectionEventListener = ClientConnectionEventListener.metricCollecting(registry);
    }

    @Test
    void shouldCollectConnectionPoolEvents() throws Exception {
        final InetSocketAddress localAddress = new InetSocketAddress("10.10.10.10", 3333);
        final InetSocketAddress remoteAddress = new InetSocketAddress("10.10.10.11", 3333);

        final String pendingABMetricKey = "armeria.client.connection.pool.size#value{desired.protocol=H1," +
                                          "local.ip=10.10.10.10,remote.ip=10.10.10.11,state=pending}";
        final String failedABMetricKey = "armeria.client.connections#count{desired.protocol=H1," +
                                         "remote.ip=10.10.10.11,state=failed}";
        final String openedABMetricKey = "armeria.client.connections#count{local.ip=10.10.10.10," +
                                         "protocol=H1,remote.ip=10.10.10.11,state=opened}";
        final String closedABMetricKey = "armeria.client.connections#count{local.ip=10.10.10.10," +
                                         "protocol=H1,remote.ip=10.10.10.11,state=closed}";
        final String activeABMetricKey = "armeria.client.connection.pool.size#value{local.ip=10.10.10.10," +
                                         "protocol=H1,remote.ip=10.10.10.11,state=active}";
        final String idleABMetricKey = "armeria.client.connection.pool.size#value{local.ip=10.10.10.10," +
                                       "protocol=H1,remote.ip=10.10.10.11,state=idle}";
        final String pendingBAMetricKey = "armeria.client.connection.pool.size#value{desired.protocol=H1," +
                                          "local.ip=10.10.10.11,remote.ip=10.10.10.10,state=pending}";
        final String openedBAMetricKey = "armeria.client.connections#count{local.ip=10.10.10.11," +
                                         "protocol=H1,remote.ip=10.10.10.10,state=opened}";
        final String activeBAMetricKey = "armeria.client.connection.pool.size#value{local.ip=10.10.10.11," +
                                         "protocol=H1,remote.ip=10.10.10.10,state=active}";
        final String idleBAMetricKey = "armeria.client.connection.pool.size#value{local.ip=10.10.10.11," +
                                       "protocol=H1,remote.ip=10.10.10.10,state=idle}";

        final AttributeMap attributeMap = new DefaultAttributeMap();
        final Exception e = new Exception();

        connectionEventListener.connectionPending(SessionProtocol.H1, remoteAddress, localAddress, attributeMap);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(pendingABMetricKey, 1.0);

        connectionEventListener.connectionFailed(SessionProtocol.H1, remoteAddress, localAddress, attributeMap, e, true);
        assertThat(MoreMeters.measureAll(registry)).doesNotContainKey(pendingABMetricKey);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(failedABMetricKey, 1.0);

        connectionEventListener.connectionPending(SessionProtocol.H1, remoteAddress, localAddress, attributeMap);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(pendingABMetricKey, 1.0);

        connectionEventListener.connectionPending(SessionProtocol.H1, remoteAddress, localAddress, attributeMap);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(pendingABMetricKey, 2.0);

        connectionEventListener.connectionPending(SessionProtocol.H1, remoteAddress, localAddress, attributeMap);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(pendingABMetricKey, 3.0);

        connectionEventListener.connectionFailed(SessionProtocol.H1, remoteAddress, localAddress, attributeMap, e, true);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(pendingABMetricKey, 2.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(failedABMetricKey, 2.0);

        connectionEventListener.connectionFailed(SessionProtocol.H1, remoteAddress, null, attributeMap, e, false);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(failedABMetricKey, 3.0);
        connectionEventListener.connectionOpened(SessionProtocol.H1, SessionProtocol.H1, remoteAddress,
                                                 localAddress, attributeMap);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(pendingABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(failedABMetricKey, 3.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedABMetricKey, 1.0);

        connectionEventListener.connectionActive(SessionProtocol.H1, remoteAddress, localAddress, attributeMap, false);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(pendingABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(failedABMetricKey, 3.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(activeABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(idleABMetricKey, 0.0);

        connectionEventListener.connectionIdle(SessionProtocol.H1, remoteAddress, localAddress, attributeMap);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(pendingABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(failedABMetricKey, 3.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(activeABMetricKey, 0.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(idleABMetricKey, 1.0);

        connectionEventListener.connectionActive(SessionProtocol.H1, remoteAddress, localAddress, attributeMap, true);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(pendingABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(failedABMetricKey, 3.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(activeABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(idleABMetricKey, 0.0);

        connectionEventListener.connectionPending(SessionProtocol.H1, localAddress, remoteAddress, attributeMap);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(pendingABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(failedABMetricKey, 3.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(activeABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(idleABMetricKey, 0.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(pendingBAMetricKey, 1.0);

        connectionEventListener.connectionOpened(SessionProtocol.H1, SessionProtocol.H1, localAddress, remoteAddress,
                                                 attributeMap);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(pendingABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(failedABMetricKey, 3.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(activeABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(idleABMetricKey, 0.0);
        assertThat(MoreMeters.measureAll(registry)).doesNotContainKey(pendingBAMetricKey);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedBAMetricKey, 1.0);

        connectionEventListener.connectionActive(SessionProtocol.H1, localAddress, remoteAddress, attributeMap, false);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(pendingABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(failedABMetricKey, 3.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(activeABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(idleABMetricKey, 0.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedBAMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(activeBAMetricKey, 1.0);

        connectionEventListener.connectionIdle(SessionProtocol.H1, localAddress, remoteAddress, attributeMap);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(pendingABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(failedABMetricKey, 3.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(activeABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(idleABMetricKey, 0.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedBAMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(activeBAMetricKey, 0.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(idleBAMetricKey, 1.0);

        connectionEventListener.connectionOpened(SessionProtocol.H1, SessionProtocol.H1, remoteAddress, localAddress,
                                                 attributeMap);
        assertThat(MoreMeters.measureAll(registry)).doesNotContainKey(pendingABMetricKey);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedABMetricKey, 2.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(activeABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(idleABMetricKey, 0.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedBAMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(activeBAMetricKey, 0.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(idleBAMetricKey, 1.0);

        connectionEventListener.connectionActive(SessionProtocol.H1, remoteAddress, localAddress, attributeMap, false);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedABMetricKey, 2.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(activeABMetricKey, 2.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(idleABMetricKey, 0.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedBAMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(activeBAMetricKey, 0.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(idleBAMetricKey, 1.0);

        connectionEventListener.connectionClosed(SessionProtocol.H1, remoteAddress, localAddress, attributeMap,
                                                 false);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedABMetricKey, 2.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(activeABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(idleABMetricKey, 0.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(closedABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedBAMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(activeBAMetricKey, 0.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(idleBAMetricKey, 1.0);

        connectionEventListener.connectionClosed(SessionProtocol.H1, localAddress, remoteAddress, attributeMap,
                                                 true);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(openedABMetricKey, 2.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(activeABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(idleABMetricKey, 0.0);
        assertThat(MoreMeters.measureAll(registry)).containsEntry(closedABMetricKey, 1.0);
        assertThat(MoreMeters.measureAll(registry)).doesNotContainKey(openedBAMetricKey);

        connectionEventListener.connectionClosed(SessionProtocol.H1, remoteAddress, localAddress, attributeMap,
                                                 false);
        assertThat(MoreMeters.measureAll(registry)).containsOnly(Maps.immutableEntry(failedABMetricKey, 3.0));
    }
}

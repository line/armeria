/*
 * Copyright 2024 LINE Corporation
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
 *
 */

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.InetSocketAddress;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MoreMeters;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ConnectionPoolMetricsTest {

    @Test
    void shouldRemoveInactiveMetricsPeriodically() {
        final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        final ConnectionPoolMetrics metrics = new ConnectionPoolMetrics(meterRegistry,
                                                                        new MeterIdPrefix("test"),
                                                                        2);

        final InetSocketAddress remoteAddr1 = new InetSocketAddress("1.1.1.1", 80);
        final InetSocketAddress localAddr1 = new InetSocketAddress("1.1.1.2", 80);
        final InetSocketAddress remoteAddr2 = new InetSocketAddress("2.2.2.1", 80);
        final InetSocketAddress localAddr2 = new InetSocketAddress("2.2.2.2", 80);
        metrics.increaseConnOpened(SessionProtocol.HTTP, remoteAddr1, localAddr1);
        metrics.increaseConnOpened(SessionProtocol.HTTP, remoteAddr1, localAddr1);
        metrics.increaseConnClosed(SessionProtocol.HTTP, remoteAddr1, localAddr1);
        metrics.increaseConnOpened(SessionProtocol.HTTP, remoteAddr2, localAddr2);
        final Map<String, Double> meters = MoreMeters.measureAll(meterRegistry);
        meters.forEach((name, value) -> {
            System.out.println(name + ": " + value);
        });
        assertThat(meters).containsEntry(
                "test.active.connections#value{local.ip=1.1.1.2,protocol=HTTP,remote.ip=1.1.1.1}", 1.0);
        assertThat(meters).containsEntry(
                "test.active.connections#value{local.ip=2.2.2.2,protocol=HTTP,remote.ip=2.2.2.1}", 1.0);

        metrics.increaseConnClosed(SessionProtocol.HTTP, remoteAddr1, localAddr1);
        await().untilAsserted(() -> {
            final Map<String, Double> meters0 = MoreMeters.measureAll(meterRegistry);
            assertThat(meters0).doesNotContainKey(
                    "test.active.connections#value{local.ip=1.1.1.2,protocol=HTTP,remote.ip=1.1.1.1}");
            assertThat(meters0).containsEntry(
                    "test.active.connections#value{local.ip=2.2.2.2,protocol=HTTP,remote.ip=2.2.2.1}", 1.0);
        });
    }

}

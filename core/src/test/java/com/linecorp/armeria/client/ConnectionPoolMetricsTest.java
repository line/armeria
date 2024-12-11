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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MoreMeters;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ConnectionPoolMetricsTest {

    @Test
    void shouldRemoveInactiveMetricsPeriodically() {
        final TestMeterRemovalListener removalListener = new TestMeterRemovalListener();
        final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        meterRegistry.config().onMeterRemoved(removalListener);
        final ConnectionPoolMetrics metrics = new ConnectionPoolMetrics(meterRegistry,
                                                                        new MeterIdPrefix("test"),
                                                                        2);

        final InetSocketAddress remoteAddr1 = new InetSocketAddress("1.1.1.1", 80);
        final InetSocketAddress localAddr1 = new InetSocketAddress("1.1.1.2", 80);
        final InetSocketAddress remoteAddr2 = new InetSocketAddress("2.2.2.1", 80);
        final InetSocketAddress localAddr2 = new InetSocketAddress("2.2.2.2", 80);
        final InetSocketAddress remoteAddr3 = new InetSocketAddress("3.3.3.1", 80);
        final InetSocketAddress localAddr3 = new InetSocketAddress("3.3.3.2", 80);
        metrics.increaseConnOpened(SessionProtocol.HTTP, remoteAddr1, localAddr1);
        metrics.increaseConnOpened(SessionProtocol.HTTP, remoteAddr1, localAddr1);
        metrics.increaseConnClosed(SessionProtocol.HTTP, remoteAddr1, localAddr1);
        metrics.increaseConnOpened(SessionProtocol.HTTP, remoteAddr2, localAddr2);
        final Map<String, Double> meters = MoreMeters.measureAll(meterRegistry);
        assertThat(meters).containsEntry(
                "test.active.connections#value{local.ip=1.1.1.2,protocol=HTTP,remote.ip=1.1.1.1}", 1.0);
        assertThat(meters).containsEntry(
                "test.active.connections#value{local.ip=2.2.2.2,protocol=HTTP,remote.ip=2.2.2.1}", 1.0);

        metrics.increaseConnClosed(SessionProtocol.HTTP, remoteAddr1, localAddr1);

        // GC is working.
        await().untilTrue(removalListener.removing);
        // Make sure metrics are collected while GC is working.
        metrics.increaseConnOpened(SessionProtocol.HTTP, remoteAddr3, localAddr3);
        // Meters wasn't updated yet.
        final Map<String, Double> meters1 = MoreMeters.measureAll(meterRegistry);
        assertThat(meters1).doesNotContainKey(
                "test.active.connections#value{local.ip=3.3.3.2,protocol=HTTP,remote.ip=3.3.3.1}");

        // GC is done.
        removalListener.waiting.complete(null);
        await().untilAsserted(() -> {
            final Map<String, Double> meters0 = MoreMeters.measureAll(meterRegistry);
            assertThat(meters0).doesNotContainKey(
                    "test.active.connections#value{local.ip=1.1.1.2,protocol=HTTP,remote.ip=1.1.1.1}");
            assertThat(meters0).containsEntry(
                    "test.active.connections#value{local.ip=2.2.2.2,protocol=HTTP,remote.ip=2.2.2.1}", 1.0);
            assertThat(meters0).containsEntry(
                    "test.active.connections#value{local.ip=3.3.3.2,protocol=HTTP,remote.ip=3.3.3.1}", 1.0);
        });
    }

    private static final class TestMeterRemovalListener implements Consumer<Meter> {

        final AtomicBoolean removing = new AtomicBoolean();
        final CompletableFuture<Void> waiting = new CompletableFuture<>();

        @Override
        public void accept(Meter meter) {
            removing.set(true);
            waiting.join();
        }
    }
}

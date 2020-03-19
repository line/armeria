/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common.metric;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import com.google.common.util.concurrent.MoreExecutors;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;

class ExecutorServiceMetricsTest {

    private static void validateMeterName(Meter meter, String executorServiceName) {
        final String name = meter.getId().getTag("name");
        if (!name.equals(executorServiceName)) {
            return;
        }

        final String meterId = meter.getId().getName();
        if (meterId.contains("executor.")) {
            assertThat(meterId).startsWith("armeria.executor.");
        }
    }

    @Test
    void executorShouldHaveArmeriaPrefix() {
        final CompositeMeterRegistry registry = Metrics.globalRegistry;
        final String executorServiceName = "test-executor";
        ExecutorServiceMetrics.monitor(registry, MoreExecutors.directExecutor(), executorServiceName);
        registry.getMeters().forEach(meter -> validateMeterName(meter, executorServiceName));
    }

    @Test
    void executorServiceShouldHaveArmeriaPrefix() {
        final CompositeMeterRegistry registry = Metrics.globalRegistry;
        final String executorServiceName = "test-executor-service";
        ExecutorServiceMetrics.monitor(registry, Executors.newSingleThreadExecutor(), executorServiceName);
        registry.getMeters().forEach(meter -> validateMeterName(meter, executorServiceName));
    }

    @Test
    void scheduledExecutorServiceShouldHaveArmeriaPrefix() {
        final CompositeMeterRegistry registry = Metrics.globalRegistry;
        final String executorServiceName = "test-scheduled-executor-service";
        ExecutorServiceMetrics.monitor(registry, Executors.newSingleThreadScheduledExecutor(),
                                       executorServiceName);
        registry.getMeters().forEach(meter -> validateMeterName(meter, executorServiceName));
    }
}

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
package com.linecorp.armeria.server.healthcheck;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandle;

import org.junit.jupiter.api.Test;

class CpuHealthCheckerTest {

    @Test
    void shouldGatherCpuUsageInformationAndCheckHealth() {
        final CpuHealthChecker cpuHealthChecker = CpuHealthChecker.of(0.3, 0.1);
        final MethodHandle processCpuUsage = cpuHealthChecker.processCpuLoad;
        final MethodHandle systemCpuUsage = cpuHealthChecker.systemCpuLoad;
        assertNotNull(processCpuUsage);
        assertNotNull(systemCpuUsage);
        assertTrue(cpuHealthChecker.isHealthy(() -> 0.2, () -> 0.01));
    }

    @Test
    void shouldGatherCpuUsageInformationAndCheckHealthWithBoundaryCpuUsage() {
        final CpuHealthChecker cpuHealthChecker = CpuHealthChecker.of(0.3, 0.1);
        final MethodHandle processCpuUsage = cpuHealthChecker.processCpuLoad;
        final MethodHandle systemCpuUsage = cpuHealthChecker.systemCpuLoad;
        assertNotNull(processCpuUsage);
        assertNotNull(systemCpuUsage);
        assertTrue(cpuHealthChecker.isHealthy(() -> 0.3, () -> 0.01));
    }

    @Test
    void shouldGatherCpuUsageInformationAndCheckHealthWithBoundaryProcessUsage() {
        final CpuHealthChecker cpuHealthChecker = CpuHealthChecker.of(0.3, 0.1);
        final MethodHandle processCpuUsage = cpuHealthChecker.processCpuLoad;
        final MethodHandle systemCpuUsage = cpuHealthChecker.systemCpuLoad;
        assertNotNull(processCpuUsage);
        assertNotNull(systemCpuUsage);
        assertTrue(cpuHealthChecker.isHealthy(() -> 0.01, () -> 0.1));
    }

    @Test
    void testSystemCpuLoadExceedsTargetCpuUsage() {
        final CpuHealthChecker cpuHealthChecker = CpuHealthChecker.of(0.3, 0.1);
        final MethodHandle processCpuUsage = cpuHealthChecker.processCpuLoad;
        final MethodHandle systemCpuUsage = cpuHealthChecker.systemCpuLoad;
        assertNotNull(processCpuUsage);
        assertNotNull(systemCpuUsage);
        assertFalse(cpuHealthChecker.isHealthy(() -> 0.4, () -> 0.1));
    }

    @Test
    void testProcessCpuLoadExceedsTargetProcessCpuUsage() {
        final CpuHealthChecker cpuHealthChecker = CpuHealthChecker.of(0.3, 0.1);
        final MethodHandle processCpuUsage = cpuHealthChecker.processCpuLoad;
        final MethodHandle systemCpuUsage = cpuHealthChecker.systemCpuLoad;
        assertNotNull(processCpuUsage);
        assertNotNull(systemCpuUsage);
        assertFalse(cpuHealthChecker.isHealthy(() -> 0.3, () -> 0.11));
    }

    @Test
    void testExceedsBoth() {
        final CpuHealthChecker cpuHealthChecker = CpuHealthChecker.of(0.3, 0.1);
        final MethodHandle processCpuUsage = cpuHealthChecker.processCpuLoad;
        final MethodHandle systemCpuUsage = cpuHealthChecker.systemCpuLoad;
        assertNotNull(processCpuUsage);
        assertNotNull(systemCpuUsage);
        assertFalse(cpuHealthChecker.isHealthy(() -> 0.4, () -> 0.11));
    }
}

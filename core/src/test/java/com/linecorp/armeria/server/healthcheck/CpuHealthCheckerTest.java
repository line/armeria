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

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

class CpuHealthCheckerTest {

    @Test
    void testGatheringCpuUsageInformation1() {
        final CpuHealthChecker cpuHealthChecker = CpuHealthChecker.of(3.0, 10);
        final Method processCpuUsage = cpuHealthChecker.processCpuUsage;
        final Method systemCpuUsage = cpuHealthChecker.systemCpuUsage;
        assertNotNull(processCpuUsage);
        assertNotNull(systemCpuUsage);
        assertTrue(cpuHealthChecker.isHealthy(() -> 2.0, () -> 1.0));
    }

    @Test
    void testGatheringCpuUsageInformation2() {
        final CpuHealthChecker cpuHealthChecker = CpuHealthChecker.of(3.0, 10);
        final Method processCpuUsage = cpuHealthChecker.processCpuUsage;
        final Method systemCpuUsage = cpuHealthChecker.systemCpuUsage;
        assertNotNull(processCpuUsage);
        assertNotNull(systemCpuUsage);
        assertTrue(cpuHealthChecker.isHealthy(() -> 3.0, () -> 1.0));
    }

    @Test
    void testGatheringCpuUsageInformation3() {
        final CpuHealthChecker cpuHealthChecker = CpuHealthChecker.of(3.0, 10);
        final Method processCpuUsage = cpuHealthChecker.processCpuUsage;
        final Method systemCpuUsage = cpuHealthChecker.systemCpuUsage;
        assertNotNull(processCpuUsage);
        assertNotNull(systemCpuUsage);
        assertTrue(cpuHealthChecker.isHealthy(() -> 1.0, () -> 10.0));
    }

    @Test
    void testGatheringCpuUsageInformation4() {
        final CpuHealthChecker cpuHealthChecker = CpuHealthChecker.of(3.0, 10);
        final Method processCpuUsage = cpuHealthChecker.processCpuUsage;
        final Method systemCpuUsage = cpuHealthChecker.systemCpuUsage;
        assertNotNull(processCpuUsage);
        assertNotNull(systemCpuUsage);
        assertFalse(cpuHealthChecker.isHealthy(() -> 4.0, () -> 10.0));
    }

    @Test
    void testGatheringCpuUsageInformation5() {
        final CpuHealthChecker cpuHealthChecker = CpuHealthChecker.of(3.0, 10);
        final Method processCpuUsage = cpuHealthChecker.processCpuUsage;
        final Method systemCpuUsage = cpuHealthChecker.systemCpuUsage;
        assertNotNull(processCpuUsage);
        assertNotNull(systemCpuUsage);
        assertFalse(cpuHealthChecker.isHealthy(() -> 3.0, () -> 11.0));
    }

    @Test
    void testGatheringCpuUsageInformation6() {
        final CpuHealthChecker cpuHealthChecker = CpuHealthChecker.of(3.0, 10);
        final Method processCpuUsage = cpuHealthChecker.processCpuUsage;
        final Method systemCpuUsage = cpuHealthChecker.systemCpuUsage;
        assertNotNull(processCpuUsage);
        assertNotNull(systemCpuUsage);
        assertFalse(cpuHealthChecker.isHealthy(() -> 4.0, () -> 11.0));
    }
}

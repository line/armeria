/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.spring;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

import com.codahale.metrics.Gauge;

/**
 * A {@link Gauge} for the CPU load average.
 * Note, this is the CPU load for the entire system, not just this JVM process.
 */
class CpuLoadMetric implements Gauge<Double> {
    private static final OperatingSystemMXBean operatingSystemMxBean =
            ManagementFactory.getOperatingSystemMXBean();

    @Override
    public Double getValue() {
        return operatingSystemMxBean.getSystemLoadAverage();
    }
}

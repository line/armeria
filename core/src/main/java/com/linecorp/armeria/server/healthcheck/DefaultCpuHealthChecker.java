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

import static java.util.Objects.requireNonNull;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Forked from <a href="https://github.com/micrometer-metrics/micrometer/blob/8339d57bef8689beb8d7a18b429a166f6595f2af/micrometer-core/src/main/java/io/micrometer/core/instrument/binder/system/ProcessorMetrics.java">ProcessorMetrics.java</a> in the micrometer core.
 */
public class DefaultCpuHealthChecker implements HealthChecker {

    private static final List<String> OPERATING_SYSTEM_BEAN_CLASS_NAMES = ImmutableList.of(
            "com.ibm.lang.management.OperatingSystemMXBean", // J9
            "com.sun.management.OperatingSystemMXBean" // HotSpot
    );

    private final OperatingSystemMXBean operatingSystemBean;

    private final Class<?> operatingSystemBeanClass;

    @Nullable
    private final Method systemCpuUsage;

    private final double targetCpuUsage;

    @Nullable
    private final Method processCpuUsage;

    private final double targetProcessCpuLoad;

    /**
     * Instantiates a new Default cpu health checker.
     *
     * @param cpuUsage the cpu usage
     * @param cpuIdle the cpu idle
     * @param processCpuUsage the process cpu usage
     * @param processCpuIdle the process cpu idle
     */
    public DefaultCpuHealthChecker(final int cpuUsage, final int cpuIdle,
                                   final int processCpuUsage, final int processCpuIdle) {
        this.targetCpuUsage = (double) cpuUsage / cpuIdle;
        this.targetProcessCpuLoad = (double) processCpuUsage / processCpuIdle;
        this.operatingSystemBean = ManagementFactory.getOperatingSystemMXBean();
        this.operatingSystemBeanClass = requireNonNull(getFirstClassFound(OPERATING_SYSTEM_BEAN_CLASS_NAMES));
        this.systemCpuUsage = requireNonNull(detectMethod("getSystemCpuLoad"));
        this.processCpuUsage = requireNonNull(detectMethod("getProcessCpuLoad"));
    }

    @Override
    public boolean isHealthy() {
        final double currentSystemCpuUsage = invoke(systemCpuUsage);
        final double currentProcessCpuUsage = invoke(processCpuUsage);
        return currentSystemCpuUsage <= targetCpuUsage && currentProcessCpuUsage <= targetProcessCpuLoad;
    }

    private double invoke(final Method method) {
        try {
            return (double) method.invoke(operatingSystemBean);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            return Double.NaN;
        }
    }

    @Nullable
    private Method detectMethod(final String name) {
        requireNonNull(name);
        try {
            // ensure the Bean we have is actually an instance of the interface
            requireNonNull(operatingSystemBeanClass.cast(operatingSystemBean));
            return operatingSystemBeanClass.getMethod(name);
        } catch (ClassCastException | NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    @Nullable
    private Class<?> getFirstClassFound(final List<String> classNames) {
        for (String className : classNames) {
            try {
                return Class.forName(className);
            } catch (ClassNotFoundException ignore) {
            }
        }
        return null;
    }
}

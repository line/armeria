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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.DoubleSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A {@link HealthChecker} that reports as unhealthy when the current
 * CPU usage or CPU load exceeds threshold. For example:
 * <pre>{@code
 * final CpuHealthChecker cpuHealthChecker = HealthChecker.of(0.1, 0.1);
 *
 * // Returns false if either is greater than 10%,
 * // or true if both are less than or equal to 10%.
 * final boolean healthy = cpuHealthChecker.isHealthy();
 * }</pre>
 */
// Forked from <a href="https://github.com/micrometer-metrics/micrometer/blob/8339d57bef8689beb8d7a18b429a166f6595f2af/micrometer-core/src/main/java/io/micrometer/core/instrument/binder/system/ProcessorMetrics.java">ProcessorMetrics.java</a> in the micrometer core.
final class CpuHealthChecker implements HealthChecker {
    private static final Logger logger = LoggerFactory.getLogger(CpuHealthChecker.class);

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static final DoubleSupplier currentSystemCpuUsageSupplier;

    private static final DoubleSupplier currentProcessCpuUsageSupplier;

    @Nullable
    private static final OperatingSystemMXBean operatingSystemBean;

    @Nullable
    private static final Class<?> operatingSystemBeanClass;

    private static final List<String> OPERATING_SYSTEM_BEAN_CLASS_NAMES = ImmutableList.of(
            "com.ibm.lang.management.OperatingSystemMXBean", // J9
            "com.sun.management.OperatingSystemMXBean" // HotSpot
    );

    @Nullable
    private static final MethodHandle systemCpuLoad;

    @Nullable
    private static final MethodHandle processCpuLoad;

    static {
        operatingSystemBeanClass = getFirstClassFound(OPERATING_SYSTEM_BEAN_CLASS_NAMES);
        operatingSystemBean = ManagementFactory.getOperatingSystemMXBean();
        final MethodHandle getCpuLoad = detectMethod("getCpuLoad");
        systemCpuLoad = getCpuLoad != null ? getCpuLoad : detectMethod("getSystemCpuLoad");
        processCpuLoad = detectMethod("getProcessCpuLoad");
        currentSystemCpuUsageSupplier = () -> invoke(systemCpuLoad);
        currentProcessCpuUsageSupplier = () -> invoke(processCpuLoad);
    }

    private final DoubleSupplier systemCpuUsageSupplier;

    private final DoubleSupplier processCpuUsageSupplier;

    @VisibleForTesting
    final double targetProcessCpuLoad;

    @VisibleForTesting
    final double targetSystemCpuUsage;

    /**
     * Instantiates a new Default cpu health checker.
     *
     * @param targetSystemCpuUsage the target cpu usage
     * @param targetProcessCpuUsage the target process cpu usage
     */
    CpuHealthChecker(double targetSystemCpuUsage, double targetProcessCpuUsage) {
        this(targetSystemCpuUsage, targetProcessCpuUsage,
             currentSystemCpuUsageSupplier, currentProcessCpuUsageSupplier);
    }

    private CpuHealthChecker(double targetSystemCpuUsage, double targetProcessCpuLoad,
                             DoubleSupplier systemCpuUsageSupplier, DoubleSupplier processCpuUsageSupplier) {
        checkArgument(targetSystemCpuUsage >= 0 && targetSystemCpuUsage <= 1.0,
                      "cpuUsage: %s (expected: 0 <= cpuUsage <= 1)", targetSystemCpuUsage);
        checkArgument(targetProcessCpuLoad >= 0 && targetProcessCpuLoad <= 1.0,
                      "processCpuLoad: %s (expected: 0 <= processCpuLoad <= 1)", targetProcessCpuLoad);
        this.targetSystemCpuUsage = targetSystemCpuUsage;
        this.targetProcessCpuLoad = targetProcessCpuLoad;
        this.systemCpuUsageSupplier = systemCpuUsageSupplier;
        this.processCpuUsageSupplier = processCpuUsageSupplier;
        checkState(operatingSystemBeanClass != null, "Unable to find an 'OperatingSystemMXBean' class");
        checkState(operatingSystemBean != null, "Unable to find an 'OperatingSystemMXBean'");
        checkState(systemCpuLoad != null, "Unable to find the method 'OperatingSystemMXBean.getCpuLoad'" +
                                           " or 'OperatingSystemMXBean.getSystemCpuLoad'");
        checkState(processCpuLoad != null,
                   "Unable to find the method 'OperatingSystemMXBean.getProcessCpuLoad'");
    }

    private static double invoke(@Nullable MethodHandle mh) {
        if (mh == null) {
            return Double.NaN;
        }

        try {
            return (double) mh.invoke(operatingSystemBean);
        } catch (Throwable e) {
            return Double.NaN;
        }
    }

    @Nullable
    @SuppressWarnings("ReturnValueIgnored")
    private static MethodHandle detectMethod(final String name) {
        if (operatingSystemBeanClass == null) {
            return null;
        }
        try {
            // ensure the Bean we have is actually an instance of the interface
            operatingSystemBeanClass.cast(operatingSystemBean);
            final Method method = operatingSystemBeanClass.getMethod(name);
            return LOOKUP.unreflect(method);
        } catch (ClassCastException | NoSuchMethodException | SecurityException | IllegalAccessException e) {
            logger.warn("Failed to detect method {}.{} for {}", operatingSystemBeanClass.getSimpleName(),
                        name, CpuHealthChecker.class.getSimpleName(), e);
            return null;
        }
    }

    @Nullable
    private static Class<?> getFirstClassFound(final List<String> classNames) {
        for (String className : classNames) {
            try {
                return Class.forName(className, false, CpuHealthChecker.class.getClassLoader());
            } catch (ClassNotFoundException ignore) {
            }
        }
        return null;
    }

    /**
     * Returns true if and only if System CPU Usage and Processes cpu usage is below the target usage.
     */
    @Override
    public boolean isHealthy() {
        return isHealthy(systemCpuUsageSupplier, processCpuUsageSupplier);
    }

    private boolean isHealthy(
            DoubleSupplier currentSystemCpuUsageSupplier, DoubleSupplier currentProcessCpuUsageSupplier) {
        final double currentSystemCpuUsage = currentSystemCpuUsageSupplier.getAsDouble();
        final double currentProcessCpuUsage = currentProcessCpuUsageSupplier.getAsDouble();
        return currentSystemCpuUsage <= targetSystemCpuUsage && currentProcessCpuUsage <= targetProcessCpuLoad;
    }
}

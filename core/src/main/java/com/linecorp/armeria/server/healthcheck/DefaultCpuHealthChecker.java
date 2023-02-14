package com.linecorp.armeria.server.healthcheck;

import static java.util.Objects.requireNonNull;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public class DefaultCpuHealthChecker implements HealthChecker{

    private static final List<String> OPERATING_SYSTEM_BEAN_CLASS_NAMES = Arrays.asList(
            "com.ibm.lang.management.OperatingSystemMXBean", // J9
            "com.sun.management.OperatingSystemMXBean" // HotSpot
    );

    private final OperatingSystemMXBean operatingSystemBean;

    private final Class<?> operatingSystemBeanClass;

    private final Method systemCpuUsage;

    private final double targetCpuUsage;

    public DefaultCpuHealthChecker(final double targetCpuUsage) {
        this.targetCpuUsage = targetCpuUsage;
        this.operatingSystemBean = ManagementFactory.getOperatingSystemMXBean();
        this.operatingSystemBeanClass = getFirstClassFound(OPERATING_SYSTEM_BEAN_CLASS_NAMES);
        this.systemCpuUsage = detectMethod("getSystemCpuLoad");
    }

    @Override
    public boolean isHealthy() {
        double cpuUsage = invoke(systemCpuUsage);
        return cpuUsage <= targetCpuUsage;
    }

    private double invoke(final Method method) {
        try {
            return (double) method.invoke(operatingSystemBean);
        }
        catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            return Double.NaN;
        }
    }

    private Method detectMethod(final String name) {
        requireNonNull(name);
        if (operatingSystemBeanClass == null) {
            return null;
        }
        try {
            // ensure the Bean we have is actually an instance of the interface
            requireNonNull(operatingSystemBeanClass.cast(operatingSystemBean));
            return operatingSystemBeanClass.getMethod(name);
        }
        catch (ClassCastException | NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    private Class<?> getFirstClassFound(final List<String> classNames) {
        for (String className : classNames) {
            try {
                return Class.forName(className);
            }
            catch (ClassNotFoundException ignore) {
            }
        }
        return null;
    }
}

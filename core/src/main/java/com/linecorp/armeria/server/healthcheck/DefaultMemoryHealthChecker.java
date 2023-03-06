package com.linecorp.armeria.server.healthcheck;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;

import com.google.common.base.MoreObjects;

public class DefaultMemoryHealthChecker implements HealthChecker {

    private final double targetHeapMemoryUtilizationRate;
    private final double targetNonHeapMemoryUtilizationRate;
    private final double targetTotalMemoryUtilizationRate;

    public DefaultMemoryHealthChecker(final double targetHeapMemoryUtilizationRate, final double targetNonHeapMemoryUtilizationRate, final double targetMemoryTotalUsage) {
        this.targetHeapMemoryUtilizationRate = targetHeapMemoryUtilizationRate;
        this.targetNonHeapMemoryUtilizationRate = targetNonHeapMemoryUtilizationRate;
        this.targetTotalMemoryUtilizationRate = targetMemoryTotalUsage;
    }

    @Override
    public boolean isHealthy() {
        final double heapMemoryUsage = getHeapMemoryUsage();
        final double nonHeapMemoryUsage = getNonHeapMemoryUsage();
        final double totalMemoryUsage = getTotalMemoryUsage();
        return heapMemoryUsage <= targetHeapMemoryUtilizationRate &&
               nonHeapMemoryUsage <= targetNonHeapMemoryUtilizationRate &&
               totalMemoryUsage <= targetTotalMemoryUtilizationRate;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("targetTotalMemoryUtilizationRate", this.targetTotalMemoryUtilizationRate)
                          .add("targetHeapMemoryUtilizationRate", this.targetHeapMemoryUtilizationRate)
                          .add("targetNonHeapMemoryUtilizationRate", this.targetNonHeapMemoryUtilizationRate)
                          .toString();
    }

    private double getHeapMemoryUsage() {
        return ManagementFactory.getMemoryPoolMXBeans().stream().filter(e -> MemoryType.HEAP.equals(e.getType())).map(e -> e.getUsage().getUsed()).mapToDouble(e -> e).sum();
    }

    private double getNonHeapMemoryUsage() {

        final BufferPoolMXBean bufferBean = ManagementFactory.getPlatformMXBean(BufferPoolMXBean.class);
        if (bufferBean != null) {
            return bufferBean.getMemoryUsed();
        } else {
            return 0;
        }
    }

    private double getTotalMemoryUsage() {
        return Runtime.getRuntime().totalMemory();
    }
}

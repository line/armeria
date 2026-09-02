package com.linecorp.armeria.server.healthcheck;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.stream.Collectors;

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
        final List<MemoryPoolMXBean> heapMemories = getHeapMemories();
        final double heapMemoryUsage = heapMemories.stream().map(MemoryPoolMXBean::getUsage).mapToDouble(MemoryUsage::getUsed).sum();
        final double maximumHeapMemory = heapMemories.stream().map(MemoryPoolMXBean::getUsage).mapToDouble(MemoryUsage::getMax).sum();
        final long runtimeMaxMemory = Runtime.getRuntime().maxMemory();

        final BufferPoolMXBean nonHeapMemoryUsage = ManagementFactory.getPlatformMXBean(BufferPoolMXBean.class);
        final double totalMemoryUsage = Runtime.getRuntime().totalMemory();
        return (heapMemoryUsage / maximumHeapMemory) <= targetHeapMemoryUtilizationRate &&
               (nonHeapMemoryUsage == null || (nonHeapMemoryUsage.getMemoryUsed() / nonHeapMemoryUsage.getTotalCapacity()) <= targetNonHeapMemoryUtilizationRate) &&
               (runtimeMaxMemory == Long.MAX_VALUE || totalMemoryUsage / runtimeMaxMemory <= targetTotalMemoryUtilizationRate);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("targetTotalMemoryUtilizationRate", this.targetTotalMemoryUtilizationRate)
                          .add("targetHeapMemoryUtilizationRate", this.targetHeapMemoryUtilizationRate)
                          .add("targetNonHeapMemoryUtilizationRate", this.targetNonHeapMemoryUtilizationRate)
                          .toString();
    }

    private List<MemoryPoolMXBean> getHeapMemories() {
        return ManagementFactory.getMemoryPoolMXBeans().stream().filter(e -> MemoryType.HEAP.equals(e.getType())).collect(Collectors.toList());
    }
}
